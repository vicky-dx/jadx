package jadx.gui.patching;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.tools.smali.dexlib2.iface.ClassDef;

import jadx.plugins.input.smali.ApkPatcher;
import jadx.plugins.input.smali.DexPatcher;

public class ModifiedDexManager {
	private static final Logger LOG = LoggerFactory.getLogger(ModifiedDexManager.class);
	private static final ModifiedDexManager INSTANCE = new ModifiedDexManager();

	public static ModifiedDexManager getInstance() {
		return INSTANCE;
	}

	// Map: dexName (e.g. "classes.dex") -> (classType -> ModifiedClass)
	private final Map<String, Map<String, ModifiedClass>> modifiedClassesByDex = new ConcurrentHashMap<>();

	private ModifiedDexManager() {
	}

	/**
	 * Registers a modified class for its target DEX file.
	 */
	public synchronized void registerModifiedClass(String dexName, String classType, byte[] assembledDexBytes) {
		String targetDex = (dexName != null && !dexName.trim().isEmpty()) ? dexName : "classes.dex";
		List<? extends ClassDef> classDefs = DexPatcher.readClasses(assembledDexBytes);
		ModifiedClass modClass = new ModifiedClass(targetDex, classType, assembledDexBytes, classDefs);
		modifiedClassesByDex.computeIfAbsent(targetDex, k -> new ConcurrentHashMap<>()).put(classType, modClass);
		LOG.info("Registered modified class '{}' in target DEX '{}'. Total modified in DEX: {}",
				classType, targetDex, modifiedClassesByDex.get(targetDex).size());
	}

	public synchronized int getModifiedClassesCount() {
		return modifiedClassesByDex.values().stream().mapToInt(Map::size).sum();
	}

	public synchronized Map<String, Map<String, ModifiedClass>> getModifiedClassesByDex() {
		return Collections.unmodifiableMap(modifiedClassesByDex);
	}

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

	public synchronized boolean hasModifications() {
		return !modifiedClassesByDex.isEmpty();
	}

	public synchronized void clear() {
		modifiedClassesByDex.clear();
	}

	/**
	 * Patches the original APK container with all modified classes registered in this session.
	 */
	public synchronized void patchApk(Path originalApk, Path outputApk) {
		Map<String, List<ClassDef>> allModified = getAllModifiedClassDefs();
		ApkPatcher.patchApkWithClasses(originalApk, outputApk, allModified);
	}
}
