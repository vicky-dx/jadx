package jadx.gui.patching.merger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SplitPackageMerger} implementation backed by
 * <a href="https://github.com/REAndroid/APKEditor">APKEditor</a>.
 *
 * <h3>Backend discovery order:</h3>
 * <ol>
 *   <li>JADX tools resource directory ({@code jadx-gui/src/main/resources/tools/APKEditor.jar})</li>
 *   <li>User application-data directory</li>
 *   <li>User-configured path (settable via JADX preferences — not yet wired, extensible)</li>
 * </ol>
 *
 * <p>The original bundle is never overwritten; output always goes to a separate path.
 */
public final class ApkEditorBackend implements SplitPackageMerger {

	private static final Logger LOG = LoggerFactory.getLogger(ApkEditorBackend.class);
	private static final String JAR_NAME = "APKEditor.jar";

	/** Extracted/located JAR path; resolved lazily on first use. */
	private Path resolvedJarPath;

	// ─────────────────────────────────────────────────────────────────────
	// SplitPackageMerger contract
	// ─────────────────────────────────────────────────────────────────────

	@Override
	public boolean isAvailable() {
		return resolveJar() != null;
	}

	@Override
	public String getBackendName() {
		return "APKEditor";
	}

	/**
	 * Merges the input XAPK/APKS bundle into a single universal APK using APKEditor.
	 *
	 * <p>Equivalent to: {@code java -jar APKEditor.jar m -i <inputBundle> -o <outputApk>}
	 *
	 * @throws IllegalStateException if APKEditor.jar cannot be located.
	 * @throws IOException           if the merge process fails or returns a non-zero exit code.
	 */
	@Override
	public Path mergeToUniversalApk(Path inputBundlePath, Path outputApkPath) throws Exception {
		Path jar = resolveJar();
		if (jar == null) {
			throw new IllegalStateException(
					"APKEditor.jar not found. Place it in the JADX tools/ directory.");
		}
		if (!Files.exists(inputBundlePath)) {
			throw new IllegalArgumentException("Input bundle not found: " + inputBundlePath);
		}

		// Ensure output parent exists and remove any 0-byte placeholder if present
		if (outputApkPath.getParent() != null) {
			Files.createDirectories(outputApkPath.getParent());
		}
		Files.deleteIfExists(outputApkPath);

		List<String> cmd = new ArrayList<>();
		cmd.add(getJavaBinary());
		cmd.add("-jar");
		cmd.add(jar.toAbsolutePath().toString());
		cmd.add("m");                                              // merge subcommand
		cmd.add("-f");                                             // force overwrite output
		cmd.add("-clean-meta");                                    // strip old signatures
		cmd.add("-i");
		cmd.add(inputBundlePath.toAbsolutePath().toString());
		cmd.add("-o");
		cmd.add(outputApkPath.toAbsolutePath().toString());

		LOG.info("[APKEditor] Running: {}", String.join(" ", cmd));

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(true);  // merge stderr into stdout for clean capture
		Process process = pb.start();

		// Capture all output for error reporting
		String output;
		try (InputStream in = process.getInputStream()) {
			output = new String(in.readAllBytes());
		}

		int exitCode = process.waitFor();
		LOG.info("[APKEditor] Exit code: {}, output:\n{}", exitCode, output);

		if (exitCode != 0) {
			throw new IOException(
					"APKEditor merge failed (exit code " + exitCode + "):\n" + output.trim());
		}
		if (!Files.exists(outputApkPath)) {
			throw new IOException(
					"APKEditor reported success but output APK was not created at: " + outputApkPath
					+ "\nOutput:\n" + output.trim());
		}

		LOG.info("[APKEditor] Merged bundle to universal APK: {}", outputApkPath);
		return outputApkPath;
	}

	// ─────────────────────────────────────────────────────────────────────
	// JAR discovery
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * Resolves the APKEditor JAR path using a priority-ordered discovery strategy.
	 * Result is cached after the first successful resolution.
	 *
	 * @return resolved {@link Path}, or {@code null} if not found anywhere.
	 */
	private synchronized Path resolveJar() {
		if (resolvedJarPath != null && Files.exists(resolvedJarPath)) {
			return resolvedJarPath;
		}

		// 1. Bundled resource inside the JADX distribution (extracted to temp on first use)
		Path extracted = tryExtractFromResources();
		if (extracted != null) {
			resolvedJarPath = extracted;
			return resolvedJarPath;
		}

		// 2. User app-data directory (e.g. %APPDATA%/jadx/tools/)
		Path appData = resolveFromAppDataDir();
		if (appData != null) {
			resolvedJarPath = appData;
			return resolvedJarPath;
		}

		LOG.warn("[APKEditor] {} not found in resources or app-data directory.", JAR_NAME);
		return null;
	}

	/**
	 * Extracts APKEditor.jar from the classpath resources (bundled in jadx-gui JAR)
	 * to a stable temp location, if not already extracted.
	 */
	private Path tryExtractFromResources() {
		try {
			InputStream resourceStream = getClass().getResourceAsStream("/tools/APKEditor.bin");
			if (resourceStream == null) {
				resourceStream = getClass().getResourceAsStream("/tools/" + JAR_NAME);
			}
			if (resourceStream == null) {
				return null;
			}
			// Extract to a stable path in the system temp dir so we don't re-extract every run
			Path dest = Path.of(System.getProperty("java.io.tmpdir"), "jadx-tools", JAR_NAME);
			Files.createDirectories(dest.getParent());
			try (InputStream in = resourceStream) {
				Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			LOG.info("[APKEditor] Extracted bundled {} to {}", JAR_NAME, dest);
			return dest;
		} catch (Exception e) {
			LOG.debug("[APKEditor] Could not extract {} from resources: {}", JAR_NAME, e.getMessage());
			return null;
		}
	}

	/** Checks the OS-appropriate user app-data directory for APKEditor.jar. */
	private Path resolveFromAppDataDir() {
		String appData = System.getenv("APPDATA");    // Windows
		if (appData == null) {
			appData = System.getProperty("user.home"); // Unix fallback
		}
		Path candidate = Path.of(appData, "jadx", "tools", JAR_NAME);
		if (Files.exists(candidate)) {
			LOG.info("[APKEditor] Found {} in app-data: {}", JAR_NAME, candidate);
			return candidate;
		}
		return null;
	}

	/** Returns the Java binary path used to execute the JAR. */
	private static String getJavaBinary() {
		String javaHome = System.getProperty("java.home");
		if (javaHome != null && !javaHome.isEmpty()) {
			String os = System.getProperty("os.name", "").toLowerCase();
			String suffix = os.contains("win") ? "java.exe" : "java";
			File javaBin = new File(javaHome, "bin" + File.separator + suffix);
			if (javaBin.exists()) {
				return javaBin.getAbsolutePath();
			}
		}
		return "java"; // rely on PATH
	}
}
