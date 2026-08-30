package jadx.plugins.input.smali;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import jadx.core.utils.exceptions.JadxRuntimeException;

public class DexPatcher {
	private static final Logger LOG = LoggerFactory.getLogger(DexPatcher.class);

	/**
	 * Read all {@link ClassDef} items from a raw DEX byte array.
	 */
	public static List<? extends ClassDef> readClasses(byte[] dexBytes) {
		return readClasses(dexBytes, Opcodes.getDefault());
	}

	public static List<? extends ClassDef> readClasses(byte[] dexBytes, Opcodes opcodes) {
		try {
			DexBackedDexFile dexFile = new DexBackedDexFile(opcodes, dexBytes);
			return List.copyOf(dexFile.getClasses());
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to read DEX classes", e);
		}
	}

	/**
	 * Patches an existing DEX byte array by replacing matching classes with updated {@link ClassDef} instances.
	 *
	 * @param originalDexBytes original DEX buffer
	 * @param modifiedClasses collection of replacement or newly added {@link ClassDef} items
	 * @return new valid DEX buffer with updated classes
	 */
	public static byte[] patchDex(byte[] originalDexBytes, Collection<? extends ClassDef> modifiedClasses) {
		return patchDex(originalDexBytes, modifiedClasses, Opcodes.getDefault());
	}

	public static byte[] patchDex(byte[] originalDexBytes, Collection<? extends ClassDef> modifiedClasses, Opcodes opcodes) {
		if (originalDexBytes == null || originalDexBytes.length == 0) {
			throw new IllegalArgumentException("Original DEX buffer cannot be empty");
		}
		if (modifiedClasses == null || modifiedClasses.isEmpty()) {
			return originalDexBytes;
		}
		try {
			DexBackedDexFile originalDexFile = new DexBackedDexFile(opcodes, originalDexBytes);
			Opcodes dexOpcodes = originalDexFile.getOpcodes() != null ? originalDexFile.getOpcodes() : opcodes;

			Map<String, ClassDef> replacements = new HashMap<>();
			for (ClassDef cd : modifiedClasses) {
				replacements.put(cd.getType(), cd);
			}

			DexPool dexPool = new DexPool(dexOpcodes);
			Set<String> processedTypes = new HashSet<>();

			// 1. Process all classes from original DEX
			for (ClassDef origClass : originalDexFile.getClasses()) {
				String type = origClass.getType();
				if (replacements.containsKey(type)) {
					ClassDef replacement = replacements.get(type);
					dexPool.internClass(replacement);
					processedTypes.add(type);
					LOG.debug("Replaced class {} in DEX pool", type);
				} else {
					dexPool.internClass(origClass);
					processedTypes.add(type);
				}
			}

			// 2. Append any new classes that didn't exist in the original DEX
			for (Map.Entry<String, ClassDef> entry : replacements.entrySet()) {
				if (!processedTypes.contains(entry.getKey())) {
					dexPool.internClass(entry.getValue());
					LOG.debug("Appended new class {} to DEX pool", entry.getKey());
				}
			}

			MemoryDataStore store = new MemoryDataStore();
			dexPool.writeTo(store);
			return store.getData();
		} catch (IOException e) {
			throw new JadxRuntimeException("Failed to write patched DEX", e);
		} catch (Exception e) {
			throw new JadxRuntimeException("Failed to patch DEX bytecode", e);
		}
	}
}
