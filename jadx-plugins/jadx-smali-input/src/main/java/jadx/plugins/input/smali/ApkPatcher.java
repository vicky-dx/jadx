package jadx.plugins.input.smali;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import jadx.core.utils.exceptions.JadxRuntimeException;

public class ApkPatcher {
	private static final Logger LOG = LoggerFactory.getLogger(ApkPatcher.class);

	/**
	 * Patches specified classes into their respective DEX files inside the original APK and produces an unsigned patched APK.
	 *
	 * @param originalApk path to original APK file
	 * @param outputApk path to target patched APK file
	 * @param modifiedClassesByDex map of DEX entry name (e.g. "classes.dex") to collection of modified {@link ClassDef} items
	 */
	public static void patchApkWithClasses(Path originalApk, Path outputApk,
			Map<String, ? extends Collection<? extends ClassDef>> modifiedClassesByDex) {
		if (!Files.exists(originalApk)) {
			throw new IllegalArgumentException("Original APK file does not exist: " + originalApk);
		}
		if (modifiedClassesByDex == null || modifiedClassesByDex.isEmpty()) {
			try {
				Files.copy(originalApk, outputApk, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				throw new JadxRuntimeException("Failed to copy APK", e);
			}
		}

		Path tempOutput = null;
		try {
			Path parentDir = outputApk.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
			tempOutput = Files.createTempFile("jadx_patch_", ".apk");

			try (ZipFile zipFile = new ZipFile(originalApk.toFile());
					ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempOutput)))) {

				Enumeration<? extends ZipEntry> entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();

					if (isSignatureEntry(name)) {
						LOG.debug("Stripping signature entry: {}", name);
						continue;
					}

					if (modifiedClassesByDex.containsKey(name)) {
						Collection<? extends ClassDef> modClasses = modifiedClassesByDex.get(name);
						byte[] origDexBytes = zipFile.getInputStream(entry).readAllBytes();
						byte[] patchedDexBytes = DexPatcher.patchDex(origDexBytes, modClasses);
						writeEntry(zos, name, patchedDexBytes, entry.getMethod());
						LOG.info("Patched and replaced DEX '{}' ({} modified classes, {} bytes) in APK",
								name, modClasses.size(), patchedDexBytes.length);
						continue;
					}

					copyZipEntry(zipFile, entry, zos);
				}
			}

			Files.move(tempOutput, outputApk, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Patched APK written successfully to: {}", outputApk);
		} catch (Exception e) {
			if (tempOutput != null) {
				try {
					Files.deleteIfExists(tempOutput);
				} catch (IOException ignored) {
				}
			}
			throw new JadxRuntimeException("Failed to patch APK container: " + e.getMessage(), e);
		}
	}

	/**
	 * Replaces specified DEX files inside an APK container and strips obsolete META-INF signatures.
	 *
	 * @param originalApk path to original APK file
	 * @param outputApk path to target patched APK file
	 * @param patchedDexMap map of DEX entry name (e.g. "classes.dex") to patched DEX byte content
	 */
	public static void patchApk(Path originalApk, Path outputApk, Map<String, byte[]> patchedDexMap) {
		if (!Files.exists(originalApk)) {
			throw new IllegalArgumentException("Original APK file does not exist: " + originalApk);
		}
		if (patchedDexMap == null || patchedDexMap.isEmpty()) {
			try {
				Files.copy(originalApk, outputApk, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (IOException e) {
				throw new JadxRuntimeException("Failed to copy APK", e);
			}
		}

		Path tempOutput = null;
		try {
			Path parentDir = outputApk.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}
			tempOutput = Files.createTempFile("jadx_patch_", ".apk");
			Set<String> writtenDexEntries = new HashSet<>();

			try (ZipFile zipFile = new ZipFile(originalApk.toFile());
					ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tempOutput)))) {

				Enumeration<? extends ZipEntry> entries = zipFile.entries();
				while (entries.hasMoreElements()) {
					ZipEntry entry = entries.nextElement();
					String name = entry.getName();

					// 1. Strip signature files for Phase 4 clean re-signing
					if (isSignatureEntry(name)) {
						LOG.debug("Stripping signature entry: {}", name);
						continue;
					}

					// 2. Replace modified DEX files
					if (patchedDexMap.containsKey(name)) {
						byte[] newDexBytes = patchedDexMap.get(name);
						writeEntry(zos, name, newDexBytes, entry.getMethod());
						writtenDexEntries.add(name);
						LOG.info("Replaced DEX entry '{}' ({} bytes) in patched APK", name, newDexBytes.length);
						continue;
					}

					// 3. Preserve all other resources, assets, and uncompressed entries
					copyZipEntry(zipFile, entry, zos);
				}

				// 4. Append any new DEX files that were not in the original APK
				for (Map.Entry<String, byte[]> extraDex : patchedDexMap.entrySet()) {
					String dexName = extraDex.getKey();
					if (!writtenDexEntries.contains(dexName)) {
						writeEntry(zos, dexName, extraDex.getValue(), ZipEntry.DEFLATED);
						LOG.info("Added new DEX entry '{}' to patched APK", dexName);
					}
				}
			}

			Files.move(tempOutput, outputApk, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Patched APK written successfully to: {}", outputApk);
		} catch (Exception e) {
			if (tempOutput != null) {
				try {
					Files.deleteIfExists(tempOutput);
				} catch (IOException ignored) {
				}
			}
			throw new JadxRuntimeException("Failed to patch APK container: " + e.getMessage(), e);
		}
	}

	private static void copyZipEntry(ZipFile zipFile, ZipEntry entry, ZipOutputStream zos) throws IOException {
		ZipEntry newEntry = new ZipEntry(entry.getName());
		newEntry.setMethod(entry.getMethod());
		newEntry.setTime(entry.getTime());
		newEntry.setExtra(entry.getExtra());
		newEntry.setComment(entry.getComment());

		if (entry.getMethod() == ZipEntry.STORED) {
			newEntry.setSize(entry.getSize());
			newEntry.setCompressedSize(entry.getCompressedSize());
			newEntry.setCrc(entry.getCrc());
		}

		zos.putNextEntry(newEntry);
		try (InputStream in = new BufferedInputStream(zipFile.getInputStream(entry))) {
			in.transferTo(zos);
		}
		zos.closeEntry();
	}

	private static void writeEntry(ZipOutputStream zos, String name, byte[] data, int method) throws IOException {
		ZipEntry newEntry = new ZipEntry(name);
		newEntry.setTime(System.currentTimeMillis());
		if (method == ZipEntry.STORED) {
			newEntry.setMethod(ZipEntry.STORED);
			newEntry.setSize(data.length);
			newEntry.setCompressedSize(data.length);
			CRC32 crc = new CRC32();
			crc.update(data);
			newEntry.setCrc(crc.getValue());
		} else {
			newEntry.setMethod(ZipEntry.DEFLATED);
		}
		zos.putNextEntry(newEntry);
		zos.write(data);
		zos.closeEntry();
	}

	private static boolean isSignatureEntry(String name) {
		if (!name.startsWith("META-INF/")) {
			return false;
		}
		String upper = name.toUpperCase();
		return upper.endsWith(".SF")
				|| upper.endsWith(".RSA")
				|| upper.endsWith(".DSA")
				|| upper.endsWith(".EC")
				|| upper.equals("META-INF/MANIFEST.MF");
	}
}
