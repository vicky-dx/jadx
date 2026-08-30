package jadx.gui.patching;

import java.util.List;

import com.android.tools.smali.dexlib2.iface.ClassDef;

public class ModifiedClass {
	private final String dexName;
	private final String classType;
	private final byte[] assembledDexBytes;
	private final List<? extends ClassDef> classDefs;
	private final long modifiedTimestamp;

	public ModifiedClass(String dexName, String classType, byte[] assembledDexBytes, List<? extends ClassDef> classDefs) {
		this.dexName = dexName;
		this.classType = classType;
		this.assembledDexBytes = assembledDexBytes;
		this.classDefs = classDefs;
		this.modifiedTimestamp = System.currentTimeMillis();
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
}
