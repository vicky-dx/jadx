package jadx.gui.patching;

import java.util.List;

import com.android.tools.smali.dexlib2.iface.ClassDef;

/**
 * Represents a class that has been modified in the current session.
 * <p>
 * Tracks both the target DEX name within an APK and — for split bundle containers
 * (XAPK / APKS) — the originating split APK name, enabling surgical container patching
 * without ever assuming {@code base.apk} as the default target.
 */
public class ModifiedClass {
	/** DEX entry name within the APK, e.g. "classes.dex", "classes2.dex". */
	private final String dexName;

	/** Fully-qualified class type descriptor, e.g. "Lcom/example/Foo;". */
	private final String classType;

	/** Assembled DEX bytes for this class. */
	private final byte[] assembledDexBytes;

	/** Parsed class definitions from the assembled DEX bytes. */
	private final List<? extends ClassDef> classDefs;

	/** Epoch-millis timestamp of when this modification was registered. */
	private final long modifiedTimestamp;

	/**
	 * Name of the originating split APK within a bundle container, e.g.
	 * "config.arm64_v8a.apk". {@code null} for standalone APK or raw DEX inputs.
	 */
	private final String sourceApkName;

	/** Standalone APK / raw DEX constructor — no split APK context. */
	public ModifiedClass(String dexName, String classType, byte[] assembledDexBytes,
			List<? extends ClassDef> classDefs) {
		this(dexName, classType, assembledDexBytes, classDefs, null);
	}

	/**
	 * Split bundle constructor — carries {@code sourceApkName} for container-aware patching.
	 *
	 * @param sourceApkName the split APK filename inside the container (e.g. "base.apk",
	 *                      "config.arm64_v8a.apk"); may be {@code null} for non-split inputs.
	 */
	public ModifiedClass(String dexName, String classType, byte[] assembledDexBytes,
			List<? extends ClassDef> classDefs, String sourceApkName) {
		this.dexName = dexName;
		this.classType = classType;
		this.assembledDexBytes = assembledDexBytes;
		this.classDefs = classDefs;
		this.modifiedTimestamp = System.currentTimeMillis();
		this.sourceApkName = sourceApkName;
	}

	public String getDexName() {
		return dexName;
	}

	public String getClassType() {
		return classType;
	}

	public byte[] getAssembledDexBytes() {
		return assembledDexBytes;
	}

	public List<? extends ClassDef> getClassDefs() {
		return classDefs;
	}

	public long getModifiedTimestamp() {
		return modifiedTimestamp;
	}

	/**
	 * Returns the originating split APK name inside a bundle container, or {@code null} if
	 * this class was loaded from a standalone APK or raw DEX file.
	 */
	public String getSourceApkName() {
		return sourceApkName;
	}

	/** Returns {@code true} if this class originated from a named split inside a bundle. */
	public boolean hasSourceApk() {
		return sourceApkName != null && !sourceApkName.isEmpty();
	}
}
