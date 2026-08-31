package jadx.gui.patching;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.gui.patching.PackageTypeDetector.PackageType;

import static org.assertj.core.api.Assertions.assertThat;

public class PackageTypeDetectorTest {

	@Test
	public void testDetectRawDex(@TempDir Path tempDir) throws Exception {
		Path dexFile = tempDir.resolve("test.dex");
		byte[] dexHeader = new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0};
		Files.write(dexFile, dexHeader);

		PackageType type = PackageTypeDetector.detect(dexFile);
		assertThat(type).isEqualTo(PackageType.RAW_DEX);
	}

	@Test
	public void testDetectStandaloneApk(@TempDir Path tempDir) throws Exception {
		Path apkFile = tempDir.resolve("app.apk");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(apkFile)))) {
			zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
			zos.write("dummy-manifest".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();

			zos.putNextEntry(new ZipEntry("classes.dex"));
			zos.write("dummy-dex".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}

		PackageType type = PackageTypeDetector.detect(apkFile);
		assertThat(type).isEqualTo(PackageType.STANDALONE_APK);
	}

	@Test
	public void testDetectXapkBundle(@TempDir Path tempDir) throws Exception {
		Path xapkFile = tempDir.resolve("bundle.xapk");
		String manifestJson = "{\"xapk_version\": 2, \"package_name\": \"com.test\", \"split_apks\": [{\"file\": \"base.apk\"}]}";
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(xapkFile)))) {
			zos.putNextEntry(new ZipEntry("manifest.json"));
			zos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}

		PackageType type = PackageTypeDetector.detect(xapkFile);
		assertThat(type).isEqualTo(PackageType.XAPK_BUNDLE);
	}

	@Test
	public void testDetectApksBundle(@TempDir Path tempDir) throws Exception {
		Path apksFile = tempDir.resolve("bundle.apks");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(apksFile)))) {
			zos.putNextEntry(new ZipEntry("toc.pb"));
			zos.write("dummy-toc".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}

		PackageType type = PackageTypeDetector.detect(apksFile);
		assertThat(type).isEqualTo(PackageType.APKS_BUNDLE);
	}

	@Test
	public void testDetectAabBundle(@TempDir Path tempDir) throws Exception {
		Path aabFile = tempDir.resolve("bundle.aab");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(aabFile)))) {
			zos.putNextEntry(new ZipEntry("base/manifest/AndroidManifest.xml"));
			zos.write("dummy-manifest".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}

		PackageType type = PackageTypeDetector.detect(aabFile);
		assertThat(type).isEqualTo(PackageType.AAB_BUNDLE);
	}

	@Test
	public void testDetectUnknownFormat(@TempDir Path tempDir) throws Exception {
		Path textFile = tempDir.resolve("note.txt");
		Files.writeString(textFile, "Hello World");

		PackageType type = PackageTypeDetector.detect(textFile);
		assertThat(type).isEqualTo(PackageType.UNKNOWN_OR_UNSUPPORTED);
	}
}
