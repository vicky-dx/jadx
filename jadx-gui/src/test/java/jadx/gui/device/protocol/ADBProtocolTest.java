package jadx.gui.device.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.gui.patching.adb.AdbDeployer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ADBProtocolTest {

	@Test
	public void testValidSerials() throws IOException {
		String[] validSerials = {
				"192.168.1.10:5555",
				"10.0.0.1:37895",
				"[fe80::1]:5555",
				"emulator-5554",
				"0a388e93",
				"adb-35121FDJH000R8-xyMD0H",
				"adb-RZ8M42CFSXA-1Kz6LW (2)._adb-tls-connect._tcp"
		};

		byte[] simulatedAdbResponse = "OKAY12345678".getBytes(StandardCharsets.UTF_8);

		for (String serial : validSerials) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayInputStream in = new ByteArrayInputStream(simulatedAdbResponse);

			boolean result = ADB.setSerial(serial, out, in);
			assertThat(result).isTrue();
			assertThat(out.toString(StandardCharsets.UTF_8)).contains(serial);
		}
	}

	@Test
	public void testInvalidSerialsRejected() {
		String[] invalidSerials = {
				null,
				"",
				"device;rm -rf /",
				"device\nkill-server",
				"device\r\n",
				"device'quote"
		};

		for (String serial : invalidSerials) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);

			assertThatThrownBy(() -> ADB.setSerial(serial, out, in))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	public void testAdbDeployerCustomPathResolution(@TempDir Path tempDir) throws IOException {
		Path dummyAdb = tempDir.resolve("my-adb.exe");
		Files.writeString(dummyAdb, "#!/bin/sh");

		String resolved = AdbDeployer.resolveAdb(dummyAdb.toString());
		assertThat(resolved).isEqualTo(dummyAdb.toAbsolutePath().toString());
	}
}
