package jadx.gui.patching.merger;

import java.nio.file.Path;

/**
 * Strategy interface for merging multi-APK bundle formats (XAPK, APKS) into a single
 * universal APK file.
 *
 * <p>Implementations are fully interchangeable (Open/Closed Principle). The JADX export
 * and deploy pipeline depends only on this interface, not on any specific merge engine
 * (e.g. apkeditor, bundletool). Switching backends requires zero changes to callers.
 */
public interface SplitPackageMerger {

	/**
	 * Returns {@code true} if this backend is ready to use (binary / JAR is available,
	 * runtime is usable, etc.). Must be checked before calling {@link #mergeToUniversalApk}.
	 */
	boolean isAvailable();

	/**
	 * Merges the given split bundle into a single universal APK.
	 *
	 * <p>The original bundle at {@code inputBundlePath} is <b>never modified</b>.
	 *
	 * @param inputBundlePath path to the XAPK or APKS bundle (read-only).
	 * @param outputApkPath   path where the merged universal APK will be written.
	 * @return the resolved path of the written universal APK (same as {@code outputApkPath}).
	 * @throws Exception if the merge fails for any reason; caller must present an error.
	 */
	Path mergeToUniversalApk(Path inputBundlePath, Path outputApkPath) throws Exception;

	/**
	 * Returns a human-readable name of this backend, e.g. "APKEditor", "bundletool".
	 * Used in log messages and error dialogs.
	 */
	String getBackendName();
}
