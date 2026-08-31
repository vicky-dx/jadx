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
 * {@link BundleContainer} implementation for APKS bundles (bundletool output).
 *
 * <p>An APKS bundle is a ZIP archive containing:
 * <ul>
 *   <li>{@code toc.pb} — protobuf table of contents</li>
 *   <li>One or more split APK files (e.g. {@code splits/base-master.apk}, {@code splits/base-arm64_v8a.apk})</li>
 * </ul>
 *
 * <h3>Key invariants:</h3>
 * <ul>
 *   <li>The original APKS bundle is <b>always read-only</b>.</li>
 *   <li>Target split APK is resolved <b>strictly from {@code ModifiedClass.sourceApkName}</b>.</li>
 *   <li>All unmodified entries are streamed directly from the original container.</li>
 * </ul>
 */
public final class ApksHandler extends BundleContainer {

	private static final Logger LOG = LoggerFactory.getLogger(ApksHandler.class);

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
					LOG.debug("[ApksHandler] Found split APK: {}", name);
				}
			}
		}
		return splits;
	}

	@Override
	public Path locateTargetSplit(Path extractedDir, String splitApkName) {
		Path candidate = extractedDir.resolve(splitApkName);
		if (Files.exists(candidate)) {
			return candidate;
		}
		LOG.warn("[ApksHandler] Split '{}' not found in: {}", splitApkName, extractedDir);
		return null;
	}

	@Override
	public Path patchTargetApk(Path splitApkPath, Map<String, byte[]> patchedDexData) throws Exception {
		Path output = Files.createTempFile("jadx_apks_split_", ".apk");
		ApkPatcher.patchApk(splitApkPath, output, patchedDexData);
		LOG.info("[ApksHandler] Patched split: {} -> {}", splitApkPath.getFileName(), output);
		return output;
	}

	@Override
	public void rebuildContainer(Path originalBundlePath, Path outputBundlePath,
			Map<String, Path> patchedSplits) throws Exception {
		rebuildApks(originalBundlePath, outputBundlePath, patchedSplits);
	}

	// ─────────────────────────────────────────────────────────────────────
	// High-level patch entry point
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * Patches the APKS container: for each modified split APK (keyed by its exact name as
	 * stored in the container), substitutes the patched split and streams all other entries.
	 *
	 * <p>Target split is resolved strictly from {@link ModifiedClass#getSourceApkName()}.
	 * The split name may include a path prefix (e.g. {@code "splits/base-master.apk"}).
	 *
	 * @param originalApks       original read-only APKS bundle path.
	 * @param outputApks         destination for the patched APKS bundle.
	 * @param modifiedBySourceApk map of split APK name → modified classes inside it.
	 */
	public void patchApks(Path originalApks, Path outputApks,
			Map<String, List<ModifiedClass>> modifiedBySourceApk) throws Exception {

		java.util.Map<String, Path> patchedSplits = new java.util.LinkedHashMap<>();

		for (Map.Entry<String, List<ModifiedClass>> entry : modifiedBySourceApk.entrySet()) {
			String splitName = entry.getKey();
			List<ModifiedClass> classes = entry.getValue();

			// Group ClassDefs by target DEX name
			java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> byDex =
					groupClassDefsByDex(classes);

			// Patch each DEX inside this split
			java.util.Map<String, byte[]> patchedDexData = new java.util.LinkedHashMap<>();
			Path tempSplit = extractEntryToTemp(originalApks, splitName);
			if (tempSplit == null) {
				throw new IllegalStateException(
						"Split '" + splitName + "' not found inside APKS bundle: " + originalApks);
			}
			try (ZipFile splitZip = new ZipFile(tempSplit.toFile())) {
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
					LOG.info("[ApksHandler] Patched DEX '{}' in split '{}' ({} bytes)",
							dexName, splitName, patched.length);
				}
			} finally {
				Files.deleteIfExists(tempSplit);
			}

			Path patchedSplit = Files.createTempFile("jadx_apks_split_patched_", ".apk");
			ApkPatcher.patchApk(tempSplit, patchedSplit, patchedDexData);
			patchedSplits.put(splitName, patchedSplit);
			LOG.info("[ApksHandler] Built patched split for '{}': {}", splitName, patchedSplit);
		}

		rebuildApks(originalApks, outputApks, patchedSplits);

		for (Path tmp : patchedSplits.values()) {
			Files.deleteIfExists(tmp);
		}
	}

	// ─────────────────────────────────────────────────────────────────────
	// Private helpers
	// ─────────────────────────────────────────────────────────────────────

	private static void rebuildApks(Path originalApks, Path outputApks,
			Map<String, Path> patchedSplits) throws Exception {
		if (outputApks.getParent() != null) {
			Files.createDirectories(outputApks.getParent());
		}
		Path tempOut = Files.createTempFile("jadx_apks_rebuild_", ".apks");
		try {
			try (ZipFile original = new ZipFile(originalApks.toFile());
					ZipOutputStream zos = new ZipOutputStream(
							new BufferedOutputStream(Files.newOutputStream(tempOut)))) {

				Enumeration<? extends ZipEntry> entries = original.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();

					if (patchedSplits.containsKey(name)) {
						byte[] patchedBytes = Files.readAllBytes(patchedSplits.get(name));
						ZipEntry newEntry = new ZipEntry(name);
						newEntry.setTime(System.currentTimeMillis());
						zos.putNextEntry(newEntry);
						zos.write(patchedBytes);
						zos.closeEntry();
						LOG.info("[ApksHandler] Substituted split '{}' in rebuilt APKS ({} bytes)",
								name, patchedBytes.length);
					} else {
						copyEntry(original, entry, zos);
					}
				}
			}
			Files.move(tempOut, outputApks, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("[ApksHandler] Rebuilt APKS bundle written to: {}", outputApks);
		} catch (Exception e) {
			Files.deleteIfExists(tempOut);
			throw e;
		}
	}

	private static Path extractEntryToTemp(Path bundlePath, String entryName) throws IOException {
		try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
			ZipEntry entry = zip.getEntry(entryName);
			if (entry == null) {
				return null;
			}
			Path temp = Files.createTempFile("jadx_apks_entry_", ".apk");
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
