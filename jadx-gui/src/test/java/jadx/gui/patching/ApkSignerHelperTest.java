package jadx.gui.patching;

import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.android.apksig.ApkVerifier;

import static org.assertj.core.api.Assertions.assertThat;

public class ApkSignerHelperTest {

	@Test
	public void testAlignAndSignApk(@TempDir Path tempDir) throws Exception {
		// 1. Create a mock APK with stored entries and deflated entries
		Path unsignedApk = tempDir.resolve("test_unsigned.apk");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(unsignedApk)))) {
			// Add a STORED entry (e.g. classes.dex or uncompressed asset)
			byte[] dexBytes = new byte[]{0x64, 0x65, 0x78, 0x0a, 0x30, 0x33, 0x35, 0x00, 1, 2, 3, 4};
			ZipEntry storedEntry = new ZipEntry("classes.dex");
			storedEntry.setMethod(ZipEntry.STORED);
			storedEntry.setSize(dexBytes.length);
			storedEntry.setCompressedSize(dexBytes.length);
			CRC32 crc = new CRC32();
			crc.update(dexBytes);
			storedEntry.setCrc(crc.getValue());
			zos.putNextEntry(storedEntry);
			zos.write(dexBytes);
			zos.closeEntry();

			// Add a DEFLATED resource entry
			ZipEntry deflatedEntry = new ZipEntry("res/layout/main.xml");
			deflatedEntry.setMethod(ZipEntry.DEFLATED);
			zos.putNextEntry(deflatedEntry);
			zos.write("<xml></xml>".getBytes());
			zos.closeEntry();

			// Add AndroidManifest.xml
			ZipEntry manifestEntry = new ZipEntry("AndroidManifest.xml");
			manifestEntry.setMethod(ZipEntry.DEFLATED);
			zos.putNextEntry(manifestEntry);
			zos.write("<manifest></manifest>".getBytes());
			zos.closeEntry();
		}

		// 2. ZipAlign step
		Path alignedApk = tempDir.resolve("test_aligned.apk");
		ZipAligner.align(unsignedApk, alignedApk);
		assertThat(Files.exists(alignedApk)).isTrue();
		assertThat(Files.size(alignedApk)).isGreaterThan(0);

		// 3. Signing step with Debug Certificate
		Path signedApk = tempDir.resolve("test_signed.apk");
		ApkSignerHelper.sign(alignedApk, signedApk, Collections.singletonList(ApkSignerHelper.getDefaultDebugSignerConfig()));
		assertThat(Files.exists(signedApk)).isTrue();
		assertThat(Files.size(signedApk)).isGreaterThan(Files.size(alignedApk));

		// 4. Verify signature cryptographically using Google's ApkVerifier
		ApkVerifier verifier = new ApkVerifier.Builder(signedApk.toFile())
				.setMinCheckedPlatformVersion(21)
				.setMaxCheckedPlatformVersion(34)
				.build();
		ApkVerifier.Result result = verifier.verify();

		assertThat(result.isVerified())
				.as("APK signature verification should succeed")
				.isTrue();
		assertThat(result.isVerifiedUsingV1Scheme())
				.as("v1 signature should be present")
				.isTrue();
		assertThat(result.isVerifiedUsingV2Scheme())
				.as("v2 signature should be present")
				.isTrue();
		assertThat(result.isVerifiedUsingV3Scheme())
				.as("v3 signature should be present")
				.isTrue();
	}

	@Test
	public void testResolveMinSdkVersionFallback(@TempDir Path tempDir) throws Exception {
		Path dummyApk = tempDir.resolve("dummy.apk");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(dummyApk)))) {
			ZipEntry manifestEntry = new ZipEntry("AndroidManifest.xml");
			zos.putNextEntry(manifestEntry);
			zos.write("plain-text".getBytes());
			zos.closeEntry();
		}
		int minSdk = ApkSignerHelper.resolveMinSdkVersion(dummyApk);
		assertThat(minSdk).isEqualTo(21);
	}
}
