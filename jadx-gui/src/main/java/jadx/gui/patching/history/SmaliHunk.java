package jadx.gui.patching.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an atomic hunk of changes inside a Smali method.
 */
public class SmaliHunk {
	public enum HunkType {
		INSERT,
		DELETE,
		REPLACE
	}

	private final int hunkIndex;
	private final String methodSignature;
	private final HunkType type;

	private final List<String> originalLines;
	private final List<String> modifiedLines;
	private final List<String> contextBefore;
	private final List<String> contextAfter;

	private final String originalFingerprint;
	private final String modifiedFingerprint;
	private final String contextFingerprint;

	private final int startLineInEditor;
	private final int endLineInEditor;
	private final int addedCount;
	private final int deletedCount;
	private final double relativePositionHint;

	public SmaliHunk(int hunkIndex, String methodSignature, HunkType type,
			List<String> originalLines, List<String> modifiedLines,
			List<String> contextBefore, List<String> contextAfter,
			int startLineInEditor, int endLineInEditor, double relativePositionHint) {
		this.hunkIndex = hunkIndex;
		this.methodSignature = Objects.requireNonNull(methodSignature);
		this.type = Objects.requireNonNull(type);
		this.originalLines = originalLines != null ? Collections.unmodifiableList(new ArrayList<>(originalLines)) : Collections.emptyList();
		this.modifiedLines = modifiedLines != null ? Collections.unmodifiableList(new ArrayList<>(modifiedLines)) : Collections.emptyList();
		this.contextBefore = contextBefore != null ? Collections.unmodifiableList(new ArrayList<>(contextBefore)) : Collections.emptyList();
		this.contextAfter = contextAfter != null ? Collections.unmodifiableList(new ArrayList<>(contextAfter)) : Collections.emptyList();

		this.startLineInEditor = startLineInEditor;
		this.endLineInEditor = endLineInEditor;
		this.addedCount = this.modifiedLines.size();
		this.deletedCount = this.originalLines.size();
		this.relativePositionHint = relativePositionHint;

		this.originalFingerprint = buildFingerprint(this.originalLines);
		this.modifiedFingerprint = buildFingerprint(this.modifiedLines);
		this.contextFingerprint = buildFingerprint(this.contextBefore) + "|||" + buildFingerprint(this.contextAfter);
	}

	private static String buildFingerprint(List<String> lines) {
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				sb.append(trimmed).append('\n');
			}
		}
		return sb.toString();
	}

	public int getHunkIndex() {
		return hunkIndex;
	}

	public String getMethodSignature() {
		return methodSignature;
	}

	public HunkType getType() {
		return type;
	}

	public HunkType getHunkType() {
		return type;
	}

	public List<String> getOriginalLines() {
		return originalLines;
	}

	public List<String> getModifiedLines() {
		return modifiedLines;
	}

	public List<String> getContextBefore() {
		return contextBefore;
	}

	public List<String> getContextAfter() {
		return contextAfter;
	}

	public String getOriginalFingerprint() {
		return originalFingerprint;
	}

	public String getModifiedFingerprint() {
		return modifiedFingerprint;
	}

	public String getContextFingerprint() {
		return contextFingerprint;
	}

	public int getStartLineInEditor() {
		return startLineInEditor;
	}

	public int getEndLineInEditor() {
		return endLineInEditor;
	}

	public int getAddedCount() {
		return addedCount;
	}

	public int getDeletedCount() {
		return deletedCount;
	}

	public double getRelativePositionHint() {
		return relativePositionHint;
	}

	public String getDiffBadge() {
		if (addedCount > 0 && deletedCount > 0) {
			return "+" + addedCount + " -" + deletedCount;
		}
		if (addedCount > 0) {
			return "+" + addedCount;
		}
		if (deletedCount > 0) {
			return "-" + deletedCount;
		}
		return "0";
	}

	public String getSummary() {
		return "Change #" + hunkIndex + " (lines " + startLineInEditor + "-" + Math.max(startLineInEditor, endLineInEditor) + ")  " + getDiffBadge();
	}

	@Override
	public String toString() {
		return getSummary();
	}
}
