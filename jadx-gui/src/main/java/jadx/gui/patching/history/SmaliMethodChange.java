package jadx.gui.patching.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Container representing a modified, added, or deleted Smali method or field.
 */
public class SmaliMethodChange {
	public enum ChangeType {
		MODIFIED,
		ADDED,
		DELETED,
		FIELD_MODIFIED
	}

	private final String classType;
	private final String methodSignature;
	private final String methodName;
	private final ChangeType changeType;
	private final String beforeMethodCode;
	private final String afterMethodCode;
	private final int startLineInEditor;
	private final int endLineInEditor;
	private final List<SmaliHunk> hunks;

	public SmaliMethodChange(String classType, String methodSignature, String methodName,
			ChangeType changeType, String beforeMethodCode, String afterMethodCode,
			int startLineInEditor, int endLineInEditor, List<SmaliHunk> hunks) {
		this.classType = Objects.requireNonNull(classType);
		this.methodSignature = Objects.requireNonNull(methodSignature);
		this.methodName = methodName != null ? methodName : methodSignature;
		this.changeType = Objects.requireNonNull(changeType);
		this.beforeMethodCode = beforeMethodCode != null ? beforeMethodCode : "";
		this.afterMethodCode = afterMethodCode != null ? afterMethodCode : "";
		this.startLineInEditor = startLineInEditor;
		this.endLineInEditor = endLineInEditor;
		this.hunks = hunks != null ? Collections.unmodifiableList(new ArrayList<>(hunks)) : Collections.emptyList();
	}

	public String getClassType() {
		return classType;
	}

	public String getMethodSignature() {
		return methodSignature;
	}

	public String getMethodName() {
		return methodName;
	}

	public ChangeType getChangeType() {
		return changeType;
	}

	public String getBeforeMethodCode() {
		return beforeMethodCode;
	}

	public String getAfterMethodCode() {
		return afterMethodCode;
	}

	public int getStartLineInEditor() {
		return startLineInEditor;
	}

	public int getEndLineInEditor() {
		return endLineInEditor;
	}

	public List<SmaliHunk> getHunks() {
		return hunks;
	}

	public boolean isAddedMethod() {
		return changeType == ChangeType.ADDED;
	}

	public boolean isDeletedMethod() {
		return changeType == ChangeType.DELETED;
	}

	public boolean isModifiedMethod() {
		return changeType == ChangeType.MODIFIED;
	}

	public boolean isMethod() {
		return changeType != ChangeType.FIELD_MODIFIED;
	}

	public boolean hasHunks() {
		return !hunks.isEmpty();
	}

	public int getStartLineInDocument() {
		return startLineInEditor;
	}

	public int getTotalAdded() {
		if (isAddedMethod()) {
			return countNonEmptyLines(afterMethodCode);
		}
		return hunks.stream().mapToInt(SmaliHunk::getAddedCount).sum();
	}

	public int getTotalDeleted() {
		if (isDeletedMethod()) {
			return countNonEmptyLines(beforeMethodCode);
		}
		return hunks.stream().mapToInt(SmaliHunk::getDeletedCount).sum();
	}

	private static int countNonEmptyLines(String str) {
		if (str == null || str.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (String line : str.split("\n")) {
			if (!line.trim().isEmpty()) {
				count++;
			}
		}
		return count;
	}

	public String getDiffBadge() {
		int add = getTotalAdded();
		int del = getTotalDeleted();
		if (add > 0 && del > 0) {
			return "+" + add + " -" + del;
		}
		if (add > 0) {
			return "+" + add;
		}
		if (del > 0) {
			return "-" + del;
		}
		return "0";
	}

	public String getDisplayTitle() {
		switch (changeType) {
			case ADDED:
				return "▸ NEW METHOD: " + methodSignature + "  " + getDiffBadge();
			case DELETED:
				return "▸ DELETED METHOD: " + methodSignature + "  " + getDiffBadge();
			case FIELD_MODIFIED:
				return "● Field: " + methodSignature + "  " + getDiffBadge();
			default:
				return "▾ " + methodSignature + "  " + getDiffBadge();
		}
	}

	@Override
	public String toString() {
		return getDisplayTitle();
	}
}
