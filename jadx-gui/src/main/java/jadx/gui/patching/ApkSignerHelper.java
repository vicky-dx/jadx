package jadx.gui.patching;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.apksig.ApkSigner;

import jadx.core.utils.exceptions.JadxRuntimeException;

public class ApkSignerHelper {
	private static final Logger LOG = LoggerFactory.getLogger(ApkSignerHelper.class);

	private static final String DEFAULT_ALIAS = "androiddebugkey";

	/**
	 * Returns standard Android Debug signer config.
	 * Checks user's ~/.android/debug.keystore first, or creates an embedded fallback.
	 */
	public static ApkSigner.SignerConfig getDefaultDebugSignerConfig() {
		// 1. Try ~/.android/debug.keystore
		Path userDebugKeystore = Paths.get(System.getProperty("user.home"), ".android", "debug.keystore");
		if (Files.exists(userDebugKeystore)) {
			try {
				LOG.info("Loading debug keystore from: {}", userDebugKeystore);
				return loadSignerConfig(userDebugKeystore, "android".toCharArray(), DEFAULT_ALIAS, "android".toCharArray());
			} catch (Exception e) {
				LOG.warn("Failed to load ~/.android/debug.keystore ({}). Falling back to JADX debug keystore.", e.getMessage());
			}
		}

		// 2. Try ~/.jadx/jadx-debug.keystore
		Path jadxDebugKeystore = Paths.get(System.getProperty("user.home"), ".jadx", "jadx-debug.keystore");
		if (Files.exists(jadxDebugKeystore)) {
			try {
				LOG.info("Loading debug keystore from: {}", jadxDebugKeystore);
				return loadSignerConfig(jadxDebugKeystore, "android".toCharArray(), DEFAULT_ALIAS, "android".toCharArray());
			} catch (Exception e) {
				LOG.warn("Failed to load ~/.jadx/jadx-debug.keystore ({}). Recreating.", e.getMessage());
				try {
					Files.deleteIfExists(jadxDebugKeystore);
				} catch (Exception ignored) {
				}
			}
		}

		LOG.info("Creating default debug certificate");
		try {
			return createSelfSignedDebugConfig(jadxDebugKeystore);
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to load or generate debug signer config: " + e.getMessage(), e);
		}
	}

	/**
	 * Loads signer config from a user-specified custom keystore file with ephemeral password handling.
	 * Automatically probes PKCS12, JKS, and default keystore formats.
	 */
	public static ApkSigner.SignerConfig loadSignerConfig(Path keystorePath, char[] storePass, String alias, char[] keyPass) {
		if (!Files.exists(keystorePath)) {
			throw new IllegalArgumentException("Keystore file does not exist: " + keystorePath);
		}

		KeyStore keyStore = null;
		Exception loadException = null;
		String[] candidateTypes = new String[]{"PKCS12", "JKS", KeyStore.getDefaultType()};

		for (String type : candidateTypes) {
			try (InputStream is = Files.newInputStream(keystorePath)) {
				KeyStore ks = KeyStore.getInstance(type);
				ks.load(is, storePass);
				keyStore = ks;
				break;
			} catch (Exception ex) {
				loadException = ex;
			}
		}

		try {
			if (keyStore == null) {
				throw new IllegalStateException("Could not load keystore with supported types (PKCS12, JKS): "
						+ (loadException != null ? loadException.getMessage() : "Unknown error"), loadException);
			}

			String targetAlias = alias;
			if (targetAlias == null || targetAlias.trim().isEmpty()) {
				if (keyStore.aliases().hasMoreElements()) {
					targetAlias = keyStore.aliases().nextElement();
				} else {
					throw new IllegalStateException("Keystore contains no aliases");
				}
			}

			char[] pass = (keyPass != null && keyPass.length > 0) ? keyPass : storePass;
			PrivateKey privateKey = (PrivateKey) keyStore.getKey(targetAlias, pass);
			if (privateKey == null) {
				throw new IllegalStateException("Private key not found for alias: " + targetAlias);
			}

			X509Certificate certificate = (X509Certificate) keyStore.getCertificate(targetAlias);
			if (certificate == null) {
				throw new IllegalStateException("Certificate not found for alias: " + targetAlias);
			}

			return new ApkSigner.SignerConfig.Builder(targetAlias, privateKey, Collections.singletonList(certificate)).build();
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to load keystore '" + keystorePath + "': " + e.getMessage(), e);
		} finally {
			// Ephemeral password cleanup
			if (storePass != null) {
				Arrays.fill(storePass, '\0');
			}
			if (keyPass != null) {
				Arrays.fill(keyPass, '\0');
			}
		}
	}

	/**
	 * Signs the input APK and outputs a signed, verified APK with v1, v2, and v3 schemes.
	 */
	public static void sign(Path inputApk, Path outputApk, List<ApkSigner.SignerConfig> signerConfigs) {
		if (!Files.exists(inputApk)) {
			throw new IllegalArgumentException("Input APK file does not exist: " + inputApk);
		}
		if (signerConfigs == null || signerConfigs.isEmpty()) {
			throw new IllegalArgumentException("At least one SignerConfig is required");
		}
		try {
			Path parentDir = outputApk.getParent();
			if (parentDir != null) {
				Files.createDirectories(parentDir);
			}

			ApkSigner.Builder builder = new ApkSigner.Builder(signerConfigs)
					.setInputApk(inputApk.toFile())
					.setOutputApk(outputApk.toFile())
					.setMinSdkVersion(21)
					.setV1SigningEnabled(true)
					.setV2SigningEnabled(true)
					.setV3SigningEnabled(true)
					.setOtherSignersSignaturesPreserved(false)
					.setCreatedBy("JADX Patched");

			builder.build().sign();
			LOG.info("Successfully signed APK with v1, v2, v3 schemes: {}", outputApk);
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to sign APK: " + e.getMessage(), e);
		}
	}

	private static ApkSigner.SignerConfig createSelfSignedDebugConfig(Path debugKeystore) throws Exception {
		Path parent = debugKeystore.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		if (!Files.exists(debugKeystore)) {
			String keytoolBinary = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
			Path keytoolPath = Paths.get(System.getProperty("java.home"), "bin", keytoolBinary);
			if (!Files.exists(keytoolPath)) {
				keytoolPath = Paths.get(keytoolBinary);
			}

			ProcessBuilder pb = new ProcessBuilder(
					keytoolPath.toString(),
					"-genkeypair",
					"-alias", DEFAULT_ALIAS,
					"-keypass", "android",
					"-keystore", debugKeystore.toString(),
					"-storepass", "android",
					"-dname", "CN=Android Debug,O=Android,C=US",
					"-keyalg", "RSA",
					"-keysize", "2048",
					"-validity", "10000");
			Process p = pb.start();
			int exit = p.waitFor();
			if (exit != 0) {
				LOG.warn("Keytool generation returned non-zero exit code: {}", exit);
			}
		}

		if (Files.exists(debugKeystore)) {
			return loadSignerConfig(debugKeystore, "android".toCharArray(), DEFAULT_ALIAS, "android".toCharArray());
		}
		throw new IllegalStateException("Unable to generate or find debug keystore at: " + debugKeystore);
	}
}
