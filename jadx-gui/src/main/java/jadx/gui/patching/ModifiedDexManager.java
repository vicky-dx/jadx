package jadx.gui.patching;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import jadx.plugins.input.smali.ApkPatcher;
import jadx.plugins.input.smali.DexPatcher;

/**
 * Session-scoped registry of all classes modified via in-memory Smali assembly.
 * <p>
 * Supports two registration modes:
 * <ul>
 *   <li><b>Standalone APK / raw DEX</b> — keyed only by dexName.</li>
 *   <li><b>Split bundle (XAPK / APKS)</b> — additionally keyed by sourceApkName,
 *       enabling surgical per-split patching without assuming {@code base.apk}.</li>
 * </ul>
 */
public class ModifiedDexManager {
	private static final Logger LOG = LoggerFactory.getLogger(ModifiedDexManager.class);
	private static final ModifiedDexManager INSTANCE = new ModifiedDexManager();

	public static ModifiedDexManager getInstance() {
		return INSTANCE;
	}

	/**
	 * Primary registry: dexName (e.g. "classes.dex") → (classType → ModifiedClass).
	 * Used by the standalone APK / raw DEX patching pipeline.
	 */
	private final Map<String, Map<String, ModifiedClass>> modifiedClassesByDex = new ConcurrentHashMap<>();

	/**
	 * Secondary registry: sourceApkName (e.g. "config.arm64_v8a.apk") → (classType → ModifiedClass).
	 * Populated only for split-bundle inputs.
	 */
	private final Map<String, Map<String, ModifiedClass>> modifiedClassesBySourceApk = new ConcurrentHashMap<>();

	private ModifiedDexManager() {
	}

	// ─────────────────────────── Registration ───────────────────────────

	/**
	 * Registers a modified class for standalone APK or raw DEX (no split context).
	 */
	public synchronized void registerModifiedClass(String dexName, String classType, byte[] assembledDexBytes) {
		registerModifiedClass(null, dexName, classType, assembledDexBytes);
	}

	/**
	 * Registers a modified class with optional split bundle context.
	 *
	 * @param sourceApkName name of the originating split APK inside a bundle container,
	 *                      or {@code null} for standalone APK / raw DEX inputs.
	 * @param dexName       DEX entry name inside the APK, e.g. "classes.dex".
	 * @param classType     fully-qualified class type descriptor.
	 * @param assembledDexBytes assembled DEX bytes for this class.
	 */
	public synchronized void registerModifiedClass(String sourceApkName, String dexName,
			String classType, byte[] assembledDexBytes) {
		String targetDex = (dexName != null && !dexName.trim().isEmpty()) ? dexName : "classes.dex";
		List<? extends ClassDef> classDefs = DexPatcher.readClasses(assembledDexBytes);
		ModifiedClass modClass = new ModifiedClass(targetDex, classType, assembledDexBytes, classDefs, sourceApkName);

		// always index by DEX name (used by standalone APK pipeline)
		modifiedClassesByDex.computeIfAbsent(targetDex, k -> new ConcurrentHashMap<>()).put(classType, modClass);

		// additionally index by sourceApkName (used by split-container pipeline)
		if (sourceApkName != null && !sourceApkName.isEmpty()) {
			modifiedClassesBySourceApk.computeIfAbsent(sourceApkName, k -> new ConcurrentHashMap<>()).put(classType, modClass);
		}

		LOG.info("Registered modified class '{}' in DEX '{}' (sourceApk='{}').",
				classType, targetDex, sourceApkName != null ? sourceApkName : "<standalone>");
	}

	/**
	 * Unregisters a modified class when reverted back to baseline original state.
	 * Handles both descriptor format (Lpkg/Name;) and dotted format (pkg.Name).
	 */
	public synchronized void unregisterModifiedClass(String classType) {
		if (classType == null) {
			return;
		}
		String clean = classType.trim();
		String descType;
		String dottedType;
		if (clean.startsWith("L") && clean.endsWith(";")) {
			descType = clean;
			dottedType = clean.substring(1, clean.length() - 1).replace('/', '.');
		} else {
			dottedType = clean;
			descType = "L" + clean.replace('.', '/') + ";";
		}
		for (Map<String, ModifiedClass> map : modifiedClassesByDex.values()) {
			map.remove(descType);
			map.remove(dottedType);
			map.remove(clean);
		}
		for (Map<String, ModifiedClass> map : modifiedClassesBySourceApk.values()) {
			map.remove(descType);
			map.remove(dottedType);
			map.remove(clean);
		}
		LOG.info("Unregistered class '{}' (desc='{}') - reverted to baseline.", classType, descType);
	}

	// ─────────────────────────── Query helpers ───────────────────────────

	/** Returns the total number of modified classes in this session. */
	public synchronized int getModifiedClassesCount() {
		return modifiedClassesByDex.values().stream().mapToInt(Map::size).sum();
	}

	/** Returns true if any class has been modified in this session. */
	public synchronized boolean hasModifications() {
		return !modifiedClassesByDex.isEmpty();
	}

	/** Returns an unmodifiable view of the primary DEX-keyed registry. Used by UI table. */
	public synchronized Map<String, Map<String, ModifiedClass>> getModifiedClassesByDex() {
		return Collections.unmodifiableMap(modifiedClassesByDex);
	}

	/**
	 * Returns all {@link ClassDef} instances for a given DEX entry.
	 * Used by the standalone APK / raw DEX pipeline.
	 */
	public synchronized Collection<ClassDef> getModifiedClassDefsForDex(String dexName) {
		Map<String, ModifiedClass> map = modifiedClassesByDex.get(dexName);
		if (map == null || map.isEmpty()) {
			return Collections.emptyList();
		}
		List<ClassDef> result = new ArrayList<>();
		for (ModifiedClass mc : map.values()) {
			result.addAll(mc.getClassDefs());
		}
		return result;
	}

	/** Returns all modified {@link ModifiedClass} instances across all DEX files. */
	public synchronized List<ModifiedClass> getModifiedClasses() {
		return modifiedClassesByDex.values().stream()
				.flatMap(m -> m.values().stream())
				.collect(Collectors.toList());
	}

	/**
	 * Returns all modified {@link ModifiedClass} instances that originated from the given
	 * split APK name. Returns empty list for standalone inputs or unknown sourceApkName.
	 */
	public synchronized List<ModifiedClass> getModifiedClasses(String sourceApkName) {
		if (sourceApkName == null || sourceApkName.isEmpty()) {
			return Collections.emptyList();
		}
		Map<String, ModifiedClass> map = modifiedClassesBySourceApk.get(sourceApkName);
		return map != null ? Collections.unmodifiableList(new ArrayList<>(map.values())) : Collections.emptyList();
	}

	/**
	 * Returns all modified {@link ModifiedClass} instances for a specific split APK and
	 * target DEX combination. Used by {@code XapkHandler} / {@code ApksHandler} for surgical
	 * per-split-per-dex patching.
	 */
	public synchronized List<ModifiedClass> getModifiedClasses(String sourceApkName, String dexName) {
		return getModifiedClasses(sourceApkName).stream()
				.filter(mc -> dexName.equals(mc.getDexName()))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the set of distinct DEX entry names that contain modified classes.
	 * e.g. {"classes.dex", "classes2.dex"}.
	 */
	public synchronized Set<String> getModifiedDexNames() {
		return Collections.unmodifiableSet(new HashSet<>(modifiedClassesByDex.keySet()));
	}

	/**
	 * Returns the set of distinct source APK names that contain modified classes.
	 * Empty for standalone APK / raw DEX sessions.
	 */
	public synchronized Set<String> getModifiedSourceApkNames() {
		return Collections.unmodifiableSet(new HashSet<>(modifiedClassesBySourceApk.keySet()));
	}

	/**
	 * Returns a map of DEX entry name → list of all modified {@link ClassDef} instances.
	 * Used by the standalone APK patching pipeline.
	 */
	public synchronized Map<String, List<ClassDef>> getAllModifiedClassDefs() {
		Map<String, List<ClassDef>> map = new HashMap<>();
		for (Map.Entry<String, Map<String, ModifiedClass>> entry : modifiedClassesByDex.entrySet()) {
			List<ClassDef> list = new ArrayList<>();
			for (ModifiedClass mc : entry.getValue().values()) {
				list.addAll(mc.getClassDefs());
			}
			map.put(entry.getKey(), list);
		}
		return map;
	}

	/** Clears all session modifications from both registries. */
	public synchronized void clear() {
		modifiedClassesByDex.clear();
		modifiedClassesBySourceApk.clear();
	}

	// ─────────────────────────── Patch operations ───────────────────────────

	/**
	 * Patches the original APK container with all modified classes registered in this session.
	 * Original file is read-only; output is written to {@code outputApk}.
	 */
	public synchronized void patchApk(Path originalApk, Path outputApk) {
		Map<String, List<ClassDef>> allModified = getAllModifiedClassDefs();
		ApkPatcher.patchApkWithClasses(originalApk, outputApk, allModified);
	}

	/**
	 * Patches a standalone DEX file with all modified classes in this session.
	 * Original file is read-only; output is written to {@code outputDex}.
	 */
	public synchronized void patchDexFile(Path originalDex, Path outputDex) throws java.io.IOException {
		byte[] originalBytes = java.nio.file.Files.readAllBytes(originalDex);
		Collection<ClassDef> allModified = new ArrayList<>();
		for (Map<String, ModifiedClass> map : modifiedClassesByDex.values()) {
			for (ModifiedClass mc : map.values()) {
				allModified.addAll(mc.getClassDefs());
			}
		}
		byte[] patchedBytes = DexPatcher.patchDex(originalBytes, allModified);
		if (outputDex.getParent() != null) {
			java.nio.file.Files.createDirectories(outputDex.getParent());
		}
		java.nio.file.Files.write(outputDex, patchedBytes);
	}
}
