package jadx.gui.patching;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects the package type of an Android artifact using magic-byte header inspection and
 * internal ZIP structure analysis. Never relies solely on file extensions.
 *
 * <h3>Detection precedence:</h3>
 * <ol>
 *   <li>RAW_DEX — first 4 bytes are {@code dex\n}</li>
 *   <li>ZIP-based — identified by PK header, then inner entries decide the subtype:
 *     <ul>
 *       <li>XAPK_BUNDLE — contains {@code manifest.json} with xapk_version=2 and split_apks</li>
 *       <li>APKS_BUNDLE  — contains {@code toc.pb}</li>
 *       <li>AAB_BUNDLE   — contains {@code base/manifest/AndroidManifest.xml}</li>
 *       <li>STANDALONE_APK — contains {@code AndroidManifest.xml} and at least one {@code .dex}</li>
 *     </ul>
 *   </li>
 *   <li>UNKNOWN_OR_UNSUPPORTED — everything else</li>
 * </ol>
 */
public final class PackageTypeDetector {

	private static final Logger LOG = LoggerFactory.getLogger(PackageTypeDetector.class);

	/** DEX magic prefix common to all DEX versions (035, 037, 038, 039). */
	private static final byte[] DEX_MAGIC = {'d', 'e', 'x', '\n'};

	/** ZIP local-file header signature. */
	private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};

	private PackageTypeDetector() {
	}

	public enum PackageType {
		/** Raw Android DEX file (dex\n035/037/038/039). */
		RAW_DEX,
		/** Standalone APK containing AndroidManifest.xml and at least one .dex entry. */
		STANDALONE_APK,
		/** XAPK bundle: ZIP with manifest.json and XAPK v2 split definitions. */
		XAPK_BUNDLE,
		/** APKS bundle: ZIP with toc.pb entry (bundletool output). */
		APKS_BUNDLE,
		/** Android App Bundle: ZIP with base/manifest/AndroidManifest.xml. */
		AAB_BUNDLE,
		/** Unrecognised, corrupt, or explicitly unsupported format (e.g. JVM JAR). */
		UNKNOWN_OR_UNSUPPORTED
	}

	/**
	 * Detects the {@link PackageType} of the file at {@code path}.
	 *
	 * @param path path to the file to inspect; must exist.
	 * @return detected package type; never {@code null}.
	 */
	public static PackageType detect(Path path) {
		if (path == null || !Files.exists(path)) {
			return PackageType.UNKNOWN_OR_UNSUPPORTED;
		}
		try {
			byte[] header = readHeader(path, 4);
			if (matchesMagic(header, DEX_MAGIC)) {
				return PackageType.RAW_DEX;
			}
			if (matchesMagic(header, ZIP_MAGIC)) {
				return inspectZip(path);
			}
		} catch (IOException e) {
			LOG.warn("Could not read file header for type detection: {}", path, e);
		}
		return PackageType.UNKNOWN_OR_UNSUPPORTED;
	}

	// ─────────────────────────────────────────────────────────────────────
	// Private helpers
	// ─────────────────────────────────────────────────────────────────────

	/** Reads up to {@code count} bytes from the start of a file. */
	private static byte[] readHeader(Path path, int count) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			byte[] buf = new byte[count];
			int read = in.read(buf);
			if (read < count) {
				byte[] trimmed = new byte[Math.max(read, 0)];
				System.arraycopy(buf, 0, trimmed, 0, trimmed.length);
				return trimmed;
			}
			return buf;
		}
	}

	/** Returns true iff {@code header} starts with every byte in {@code magic}. */
	private static boolean matchesMagic(byte[] header, byte[] magic) {
		if (header.length < magic.length) {
			return false;
		}
		for (int i = 0; i < magic.length; i++) {
			if (header[i] != magic[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Inspects the internal ZIP entries to distinguish XAPK, APKS, AAB, and standalone APK.
	 */
	private static PackageType inspectZip(Path path) {
		try (ZipFile zip = new ZipFile(path.toFile())) {
			boolean hasManifestXml = false;
			boolean hasDex = false;
			boolean hasXapkManifest = false;
			boolean hasTocPb = false;
			boolean hasAabBase = false;

			java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();

				if ("manifest.json".equalsIgnoreCase(name)) {
					// XAPK manifest — confirm it has xapk_version=2 and split_apks
					try (InputStream in = zip.getInputStream(entry)) {
						String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
						if (json.contains("\"xapk_version\"") && json.contains("\"split_apks\"")) {
							hasXapkManifest = true;
						}
					}
				} else if ("toc.pb".equalsIgnoreCase(name)) {
					hasTocPb = true;
				} else if ("base/manifest/AndroidManifest.xml".equals(name)) {
					hasAabBase = true;
				} else if ("AndroidManifest.xml".equals(name)) {
					hasManifestXml = true;
				} else if (name.endsWith(".dex")) {
					hasDex = true;
				}
			}

			if (hasXapkManifest) {
				return PackageType.XAPK_BUNDLE;
			}
			if (hasTocPb) {
				return PackageType.APKS_BUNDLE;
			}
			if (hasAabBase) {
				return PackageType.AAB_BUNDLE;
			}
			if (hasManifestXml && hasDex) {
				return PackageType.STANDALONE_APK;
			}
		} catch (IOException e) {
			LOG.warn("ZIP inspection failed for type detection: {}", path, e);
		}
		return PackageType.UNKNOWN_OR_UNSUPPORTED;
	}
}
