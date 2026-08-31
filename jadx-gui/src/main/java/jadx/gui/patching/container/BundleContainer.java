package jadx.gui.patching.container;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jadx.gui.patching.ModifiedClass;

/**
 * Abstract base for multi-APK bundle container handlers.
 *
 * <p>Each concrete handler (e.g. {@link XapkHandler}, {@link ApksHandler}) implements the
 * four-step container patching lifecycle:
 * <ol>
 *   <li>{@link #inspect(Path)} — validate the container and enumerate its internal APKs.</li>
 *   <li>{@link #locateTargetSplit(Path, String)} — find the internal APK by name.</li>
 *   <li>{@link #patchTargetApk(Path, Map)} — patch the correct split APK in-place (temp copy).</li>
 *   <li>{@link #rebuildContainer(Path, Path, Map)} — assemble all splits back into the container.</li>
 * </ol>
 *
 * <p>The original bundle is <b>always read-only</b>. A new patched container is always written
 * to {@code outputPath}.
 */
public abstract class BundleContainer {

	/**
	 * Validates and inspects the bundle, returning names of all internal APK splits.
	 *
	 * @param bundlePath path to the original (read-only) bundle.
	 * @return list of split APK entry names found inside the container.
	 * @throws Exception if the bundle is invalid or cannot be read.
	 */
	public abstract List<String> inspect(Path bundlePath) throws Exception;

	/**
	 * Resolves the path of the named internal split APK within an already-extracted bundle.
	 * Never returns {@code base.apk} by assumption — the caller always provides the exact name.
	 *
	 * @param extractedDir directory where the bundle was previously extracted.
	 * @param splitApkName exact split APK filename, e.g. "config.arm64_v8a.apk".
	 * @return the {@link Path} to the split APK, or {@code null} if not found.
	 */
	public abstract Path locateTargetSplit(Path extractedDir, String splitApkName);

	/**
	 * Patches the given split APK with the provided modified DEX bytes.
	 *
	 * @param splitApkPath   path to the split APK to patch (treated as read-only; output written separately).
	 * @param patchedDexData map of DEX entry name → patched DEX bytes.
	 * @return path to the new, patched split APK (a temp file or sibling file).
	 * @throws Exception if patching fails.
	 */
	public abstract Path patchTargetApk(Path splitApkPath, Map<String, byte[]> patchedDexData) throws Exception;

	/**
	 * Rebuilds the container archive by substituting the patched split and preserving all
	 * other unmodified entries from the original bundle.
	 *
	 * @param originalBundlePath path to the original (read-only) bundle.
	 * @param outputBundlePath   destination for the rebuilt patched container.
	 * @param patchedSplits      map of split APK name → patched split APK path.
	 * @throws Exception if the container cannot be rebuilt.
	 */
	public abstract void rebuildContainer(Path originalBundlePath, Path outputBundlePath,
			Map<String, Path> patchedSplits) throws Exception;

	/**
	 * High-level entry point: produces a patched version of the bundle at {@code outputBundlePath}.
	 * <p>
	 * Default implementation delegates to the four abstract lifecycle methods.
	 * Subclasses may override for container-specific optimisations.
	 *
	 * @param originalBundlePath  original read-only bundle.
	 * @param outputBundlePath    patched output bundle path.
	 * @param modifiedBySourceApk map of split APK name → list of modified classes inside it.
	 * @throws Exception on any failure.
	 */
	public void patch(Path originalBundlePath, Path outputBundlePath,
			Map<String, List<ModifiedClass>> modifiedBySourceApk) throws Exception {
		// For each modified split: locate → collect patched DEX bytes → patch split APK
		java.util.Map<String, Path> patchedSplits = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, List<ModifiedClass>> entry : modifiedBySourceApk.entrySet()) {
			String splitName = entry.getKey();
			List<ModifiedClass> classes = entry.getValue();

			// Group classes by their target DEX name
			java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> byDex =
					new java.util.LinkedHashMap<>();
			for (ModifiedClass mc : classes) {
				for (com.android.tools.smali.dexlib2.iface.ClassDef cd : mc.getClassDefs()) {
					byDex.computeIfAbsent(mc.getDexName(), k -> new java.util.ArrayList<>()).add(cd);
				}
			}

			// Patch the raw DEX bytes for each target DEX
			java.util.Map<String, byte[]> patchedDexData = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> dexEntry : byDex.entrySet()) {
				// We need the original DEX bytes; they come from the split APK itself.
				// The patchTargetApk implementation handles this lookup internally.
				patchedDexData.put(dexEntry.getKey(), null); // signal: patch this DEX entry
			}

			// Locate and patch the split
			Path extractedSplit = locateTargetSplit(originalBundlePath.getParent(), splitName);
			if (extractedSplit == null) {
				throw new IllegalStateException(
						"Split APK not found in container: " + splitName + " (from " + originalBundlePath + ")");
			}
			// Build a proper patched DEX map using the full ClassDef list
			java.util.Map<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> byDexFull = byDex;
			java.util.Map<String, byte[]> resolvedDexData = new java.util.LinkedHashMap<>();
			for (Map.Entry<String, java.util.List<com.android.tools.smali.dexlib2.iface.ClassDef>> dexEntry : byDexFull.entrySet()) {
				byte[] patched = jadx.plugins.input.smali.DexPatcher.patchDex(
						readDexFromApk(extractedSplit, dexEntry.getKey()), dexEntry.getValue());
				resolvedDexData.put(dexEntry.getKey(), patched);
			}

			Path patchedSplit = patchTargetApk(extractedSplit, resolvedDexData);
			patchedSplits.put(splitName, patchedSplit);
		}

		rebuildContainer(originalBundlePath, outputBundlePath, patchedSplits);
	}

	/**
	 * Reads the raw bytes of a named DEX entry from inside an APK (ZIP) file.
	 */
	protected static byte[] readDexFromApk(Path apkPath, String dexEntryName) throws Exception {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry(dexEntryName);
			if (entry == null) {
				throw new java.io.IOException(
						"DEX entry '" + dexEntryName + "' not found in: " + apkPath);
			}
			try (java.io.InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
