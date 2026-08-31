package jadx.gui.patching.container;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.gui.patching.ModifiedClass;
import jadx.plugins.input.smali.ApkPatcher;

/**
 * {@link BundleContainer} implementation for XAPK bundles.
 *
 * <p>An XAPK is a ZIP archive containing:
 * <ul>
 *   <li>{@code manifest.json} — XAPK manifest with split APK declarations</li>
 *   <li>One or more split APK files (e.g. {@code base.apk}, {@code config.arm64_v8a.apk})</li>
 *   <li>Optional OBB data files</li>
 * </ul>
 *
 * <h3>Key invariants:</h3>
 * <ul>
 *   <li>The original XAPK is <b>always read-only</b>.</li>
 *   <li>Target split APK is resolved <b>strictly from {@code ModifiedClass.sourceApkName}</b>.
 *       {@code base.apk} is never assumed as the default target.</li>
 *   <li>Unmodified entries are streamed directly from the original XAPK.</li>
 * </ul>
 */
public final class XapkHandler extends BundleContainer {

	private static final Logger LOG = LoggerFactory.getLogger(XapkHandler.class);

	// ─────────────────────────────────────────────────────────────────────
	// BundleContainer contract
	// ─────────────────────────────────────────────────────────────────────

	@Override
	public List<String> inspect(Path bundlePath) throws Exception {
		List<String> splits = new ArrayList<>();
		try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!entry.isDirectory() && name.endsWith(".apk")) {
					splits.add(name);
					LOG.debug("[XapkHandler] Found split APK: {}", name);
				}
			}
		}
		return splits;
	}

	@Override
	public Path locateTargetSplit(Path extractedDir, String splitApkName) {
		// The XAPK handler works directly on the original bundle (streaming), not pre-extracted dirs.
		// This method is used when the caller has already extracted the container.
		Path candidate = extractedDir.resolve(splitApkName);
		if (Files.exists(candidate)) {
			return candidate;
		}
		LOG.warn("[XapkHandler] Split APK '{}' not found in: {}", splitApkName, extractedDir);
		return null;
	}

	@Override
	public Path patchTargetApk(Path splitApkPath, Map<String, byte[]> patchedDexData) throws Exception {
		Path output = Files.createTempFile("jadx_split_patched_", ".apk");
		ApkPatcher.patchApk(splitApkPath, output, patchedDexData);
		LOG.info("[XapkHandler] Patched split APK: {} -> {}", splitApkPath.getFileName(), output);
		return output;
	}

	@Override
	public void rebuildContainer(Path originalBundlePath, Path outputBundlePath,
			Map<String, Path> patchedSplits) throws Exception {
		rebuildXapk(originalBundlePath, outputBundlePath, patchedSplits);
	}

	// ─────────────────────────────────────────────────────────────────────
	// High-level patch entry point
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * Patches the XAPK container: for each modified split APK, replaces the corresponding
	 * internal entry and streams all other entries unchanged.
	 *
	 * <p>Target split APK is resolved strictly from {@link ModifiedClass#getSourceApkName()}.
	 * {@code base.apk} is <b>never assumed</b>.
	 *
	 * @param originalXapk        original read-only XAPK path.
	 * @param outputXapk          destination for the patched XAPK.
	 * @param modifiedBySourceApk map of split APK name → modified classes.
	 */
	public void patchXapk(Path originalXapk, Path outputXapk,
			Map<String, List<ModifiedClass>> modifiedBySourceApk) throws Exception {

		// Step 1: for each modified split, produce a patched split APK in temp
		java.util.Map<String, Path> patchedSplits = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, List<ModifiedClass>> entry : modifiedBySourceApk.entrySet()) {
			String splitName = entry.getKey();
			List<ModifiedClass> classes = entry.getValue();

			// Group by DEX name and collect ClassDefs
			java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> byDex =
					groupClassDefsByDex(classes);

			// Read each original DEX from inside this split APK and patch it
			java.util.Map<String, byte[]> patchedDexData = new java.util.LinkedHashMap<>();
			try (ZipFile splitZip = openSplitFromXapk(originalXapk, splitName)) {
				if (splitZip == null) {
					throw new IllegalStateException(
							"Split '" + splitName + "' not found inside XAPK: " + originalXapk);
				}
				for (Map.Entry<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> dexEntry : byDex.entrySet()) {
					String dexName = dexEntry.getKey();
					ZipEntry dexZipEntry = splitZip.getEntry(dexName);
					if (dexZipEntry == null) {
						throw new IOException("DEX '" + dexName + "' not found in split '" + splitName + "'");
					}
					byte[] origDex;
					try (InputStream in = splitZip.getInputStream(dexZipEntry)) {
						origDex = in.readAllBytes();
					}
					byte[] patched = jadx.plugins.input.smali.DexPatcher.patchDex(origDex, dexEntry.getValue());
					patchedDexData.put(dexName, patched);
					LOG.info("[XapkHandler] Patched DEX '{}' in split '{}' ({} bytes)",
							dexName, splitName, patched.length);
				}
			}

			// Build patched split APK from the extracted split bytes
			Path tempSplitApk = extractSplitToTemp(originalXapk, splitName);
			Path patchedSplit = Files.createTempFile("jadx_split_patched_", ".apk");
			ApkPatcher.patchApk(tempSplitApk, patchedSplit, patchedDexData);
			Files.deleteIfExists(tempSplitApk);

			patchedSplits.put(splitName, patchedSplit);
			LOG.info("[XapkHandler] Built patched split for '{}': {}", splitName, patchedSplit);
		}

		// Step 2: rebuild XAPK replacing modified splits, streaming others unchanged
		rebuildXapk(originalXapk, outputXapk, patchedSplits);

		// Step 3: cleanup temp split APKs
		for (Path tmp : patchedSplits.values()) {
			Files.deleteIfExists(tmp);
		}
	}

	// ─────────────────────────────────────────────────────────────────────
	// Private helpers
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * Rebuilds the XAPK container, substituting patched splits and streaming all other
	 * entries from the original XAPK unchanged.
	 */
	private static void rebuildXapk(Path originalXapk, Path outputXapk,
			Map<String, Path> patchedSplits) throws Exception {
		if (outputXapk.getParent() != null) {
			Files.createDirectories(outputXapk.getParent());
		}
		Path tempOut = Files.createTempFile("jadx_xapk_rebuild_", ".xapk");
		try {
			try (ZipFile original = new ZipFile(originalXapk.toFile());
					ZipOutputStream zos = new ZipOutputStream(
							new BufferedOutputStream(Files.newOutputStream(tempOut)))) {

				Enumeration<? extends ZipEntry> entries = original.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();

					if (patchedSplits.containsKey(name)) {
						// Substitute with patched split APK bytes
						byte[] patchedBytes = Files.readAllBytes(patchedSplits.get(name));
						writeEntry(zos, name, patchedBytes);
						LOG.info("[XapkHandler] Substituted split '{}' in rebuilt XAPK ({} bytes)",
								name, patchedBytes.length);
					} else {
						// Stream original entry unchanged
						copyEntry(original, entry, zos);
					}
				}
			}
			Files.move(tempOut, outputXapk, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("[XapkHandler] Rebuilt XAPK written to: {}", outputXapk);
		} catch (Exception e) {
			Files.deleteIfExists(tempOut);
			throw e;
		}
	}

	/**
	 * Opens a temporary ZipFile view of a named split APK embedded inside the XAPK.
	 * The caller must close the returned ZipFile.
	 */
	private static ZipFile openSplitFromXapk(Path xapkPath, String splitName) throws IOException {
		// We need a physical temp file because ZipFile requires a File, not a stream
		Path temp = extractSplitToTemp(xapkPath, splitName);
		if (temp == null) {
			return null;
		}
		// Return a ZipFile that also deletes the temp file on close
		return new ZipFile(temp.toFile()) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					Files.deleteIfExists(temp);
				}
			}
		};
	}

	/**
	 * Extracts a named entry from the XAPK to a temp file and returns its path.
	 */
	private static Path extractSplitToTemp(Path xapkPath, String splitName) throws IOException {
		try (ZipFile zip = new ZipFile(xapkPath.toFile())) {
			ZipEntry entry = zip.getEntry(splitName);
			if (entry == null) {
				return null;
			}
			Path temp = Files.createTempFile("jadx_split_", ".apk");
			try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
				Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
			}
			return temp;
		}
	}

	private static java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>>
			groupClassDefsByDex(List<ModifiedClass> classes) {
		java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> byDex =
				new java.util.LinkedHashMap<>();
		for (ModifiedClass mc : classes) {
			byDex.computeIfAbsent(mc.getDexName(), k -> new java.util.ArrayList<>())
					.addAll(mc.getClassDefs());
		}
		return byDex;
	}

	private static void writeEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
		ZipEntry e = new ZipEntry(name);
		e.setTime(System.currentTimeMillis());
		zos.putNextEntry(e);
		zos.write(data);
		zos.closeEntry();
	}

	private static void copyEntry(ZipFile source, ZipEntry entry, ZipOutputStream dest) throws IOException {
		ZipEntry newEntry = new ZipEntry(entry.getName());
		newEntry.setTime(entry.getTime());
		newEntry.setMethod(entry.getMethod());
		newEntry.setExtra(entry.getExtra());
		if (entry.getMethod() == ZipEntry.STORED) {
			newEntry.setSize(entry.getSize());
			newEntry.setCompressedSize(entry.getCompressedSize());
			newEntry.setCrc(entry.getCrc());
		}
		dest.putNextEntry(newEntry);
		try (InputStream in = new BufferedInputStream(source.getInputStream(entry))) {
			in.transferTo(dest);
		}
		dest.closeEntry();
	}
}
