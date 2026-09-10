package jadx.gui.patching.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses differences between Baseline Smali and Current Active Smali into a 3-tier
 * structure: Class -> Method/Field -> Hunk.
 */
public class SmaliMethodDiffParser {
	private static final Logger LOG = LoggerFactory.getLogger(SmaliMethodDiffParser.class);

	public static class SmaliBlock {
		private final String signature;
		private final String name;
		private final List<String> lines;
		private final String rawContent;
		private final int startLineInDocument;
		private final int endLineInDocument;
		private final boolean isMethod;

		public SmaliBlock(String signature, String name, List<String> lines,
				String rawContent, int startLineInDocument, int endLineInDocument, boolean isMethod) {
			this.signature = signature;
			this.name = name;
			this.lines = lines;
			this.rawContent = rawContent;
			this.startLineInDocument = startLineInDocument;
			this.endLineInDocument = endLineInDocument;
			this.isMethod = isMethod;
		}

		public String getSignature() {
			return signature;
		}

		public String getName() {
			return name;
		}

		public List<String> getLines() {
			return lines;
		}

		public String getRawContent() {
			return rawContent;
		}

		public int getStartLineInDocument() {
			return startLineInDocument;
		}

		public int getEndLineInDocument() {
			return endLineInDocument;
		}

		public boolean isMethod() {
			return isMethod;
		}
	}

	/**
	 * Parses and compares baseline Smali against current Smali.
	 */
	public static List<SmaliMethodChange> parseChanges(String classType, String baselineSmali, String currentSmali) {
		if (baselineSmali == null) {
			baselineSmali = "";
		}
		if (currentSmali == null) {
			currentSmali = "";
		}

		String normBaseline = baselineSmali.replace("\r\n", "\n").replace('\r', '\n');
		String normCurrent = currentSmali.replace("\r\n", "\n").replace('\r', '\n');

		if (normBaseline.equals(normCurrent)) {
			return Collections.emptyList();
		}

		Map<String, SmaliBlock> baseBlocks = extractBlocks(normBaseline);
		Map<String, SmaliBlock> currBlocks = extractBlocks(normCurrent);

		List<SmaliMethodChange> changes = new ArrayList<>();

		// 1. Process current blocks (Modified or Added)
		for (Map.Entry<String, SmaliBlock> entry : currBlocks.entrySet()) {
			String sig = entry.getKey();
			SmaliBlock curr = entry.getValue();
			SmaliBlock base = baseBlocks.get(sig);

			if (base == null) {
				// Added method or field
				SmaliMethodChange.ChangeType cType = curr.isMethod()
						? SmaliMethodChange.ChangeType.ADDED
						: SmaliMethodChange.ChangeType.FIELD_MODIFIED;

				changes.add(new SmaliMethodChange(classType, sig, curr.getName(),
						cType, "", curr.getRawContent(),
						curr.getStartLineInDocument(), curr.getEndLineInDocument(),
						Collections.emptyList()));
			} else {
				// Compare content
				if (!normalizeForCompare(base.getRawContent()).equals(normalizeForCompare(curr.getRawContent()))) {
					if (!curr.isMethod()) {
						// Modified field
						changes.add(new SmaliMethodChange(classType, sig, curr.getName(),
								SmaliMethodChange.ChangeType.FIELD_MODIFIED,
								base.getRawContent(), curr.getRawContent(),
								curr.getStartLineInDocument(), curr.getEndLineInDocument(),
								Collections.emptyList()));
					} else {
						// Modified method -> derive atomic hunks
						List<SmaliHunk> hunks = computeHunks(sig, base, curr);
						changes.add(new SmaliMethodChange(classType, sig, curr.getName(),
								SmaliMethodChange.ChangeType.MODIFIED,
								base.getRawContent(), curr.getRawContent(),
								curr.getStartLineInDocument(), curr.getEndLineInDocument(),
								hunks));
					}
				}
			}
		}

		// 2. Process deleted blocks (present in baseline but missing in current)
		for (Map.Entry<String, SmaliBlock> entry : baseBlocks.entrySet()) {
			String sig = entry.getKey();
			if (!currBlocks.containsKey(sig)) {
				SmaliBlock base = entry.getValue();
				SmaliMethodChange.ChangeType cType = base.isMethod()
						? SmaliMethodChange.ChangeType.DELETED
						: SmaliMethodChange.ChangeType.FIELD_MODIFIED;

				changes.add(new SmaliMethodChange(classType, sig, base.getName(),
						cType, base.getRawContent(), "",
						0, 0,
						Collections.emptyList()));
			}
		}

		return changes;
	}

	public static Map<String, SmaliBlock> extractBlocks(String smaliCode) {
		Map<String, SmaliBlock> blocks = new LinkedHashMap<>();
		String[] lines = smaliCode.split("\n", -1);

		boolean inMethod = false;
		String methodSig = null;
		String methodName = null;
		int methodStartLine = 0;
		List<String> methodLines = new ArrayList<>();

		for (int i = 0; i < lines.length; i++) {
			int lineNum = i + 1;
			String line = lines[i];
			String trimmed = line.trim();

			if (!inMethod) {
				if (trimmed.startsWith(".method ")) {
					inMethod = true;
					methodSig = parseMethodSignature(trimmed);
					methodName = parseMethodName(methodSig);
					methodStartLine = lineNum;
					methodLines.clear();
					methodLines.add(line);
				} else if (trimmed.startsWith(".field ")) {
					String fieldSig = parseFieldSignature(trimmed);
					String fieldName = parseFieldName(fieldSig);
					List<String> fieldLines = Collections.singletonList(line);
					blocks.put(fieldSig, new SmaliBlock(fieldSig, fieldName, fieldLines, line, lineNum, lineNum, false));
				}
			} else {
				methodLines.add(line);
				if (trimmed.equals(".end method")) {
					inMethod = false;
					String raw = String.join("\n", methodLines);
					blocks.put(methodSig, new SmaliBlock(methodSig, methodName, new ArrayList<>(methodLines), raw, methodStartLine, lineNum, true));
				}
			}
		}

		return blocks;
	}

	public static String parseMethodSignature(String methodLine) {
		// e.g. ".method public static checkLicense(Landroid/content/Context;I)Z"
		// or ".method constructor <init>()V"
		int parenOpen = methodLine.indexOf('(');
		if (parenOpen == -1) {
			return methodLine.trim();
		}
		int spaceBeforeParen = methodLine.lastIndexOf(' ', parenOpen);
		if (spaceBeforeParen != -1) {
			return methodLine.substring(spaceBeforeParen + 1).trim();
		}
		return methodLine.trim();
	}

	public static String parseMethodName(String methodSig) {
		int paren = methodSig.indexOf('(');
		if (paren != -1) {
			return methodSig.substring(0, paren).trim();
		}
		return methodSig;
	}

	public static String parseFieldSignature(String fieldLine) {
		// e.g. ".field private static final IS_PREMIUM:Z"
		int eqIdx = fieldLine.indexOf('=');
		String clean = (eqIdx != -1) ? fieldLine.substring(0, eqIdx).trim() : fieldLine.trim();
		int lastSpace = clean.lastIndexOf(' ');
		if (lastSpace != -1) {
			return clean.substring(lastSpace + 1).trim();
		}
		return clean;
	}

	public static String parseFieldName(String fieldSig) {
		int colon = fieldSig.indexOf(':');
		if (colon != -1) {
			return fieldSig.substring(0, colon).trim();
		}
		return fieldSig;
	}

	private static String normalizeForCompare(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("\r\n", "\n").replace('\r', '\n').trim();
	}

	/**
	 * Computes Myers / LCS diff hunks between baseline method lines and current method lines.
	 */
	public static List<SmaliHunk> computeHunks(String methodSig, SmaliBlock base, SmaliBlock curr) {
		List<String> baseLines = base.getLines();
		List<String> currLines = curr.getLines();

		List<DiffOp> ops = computeLineDiff(baseLines, currLines);
		if (ops.isEmpty()) {
			return Collections.emptyList();
		}

		List<SmaliHunk> hunks = new ArrayList<>();
		int hunkIdx = 1;
		int totalCurrLines = Math.max(1, currLines.size());

		int i = 0;
		while (i < ops.size()) {
			DiffOp op = ops.get(i);
			if (op.type == OpType.EQUAL) {
				i++;
				continue;
			}

			// Cluster adjacent modified/inserted/deleted operations into a single hunk
			List<String> origHunkLines = new ArrayList<>();
			List<String> modHunkLines = new ArrayList<>();
			int firstCurrLineInMethod = op.currLineIdx;

			while (i < ops.size() && ops.get(i).type != OpType.EQUAL) {
				DiffOp clusterOp = ops.get(i);
				if (clusterOp.type == OpType.DELETE) {
					origHunkLines.add(clusterOp.line);
				} else if (clusterOp.type == OpType.INSERT) {
					modHunkLines.add(clusterOp.line);
				}
				i++;
			}

			// Extract 2 lines of context before
			List<String> contextBefore = new ArrayList<>();
			int ctxStart = Math.max(0, firstCurrLineInMethod - 2);
			for (int c = ctxStart; c < firstCurrLineInMethod; c++) {
				if (c < currLines.size()) {
					contextBefore.add(currLines.get(c));
				}
			}

			// Extract 2 lines of context after
			List<String> contextAfter = new ArrayList<>();
			int afterCurrLineIdx = firstCurrLineInMethod + modHunkLines.size();
			int ctxEnd = Math.min(currLines.size(), afterCurrLineIdx + 2);
			for (int c = afterCurrLineIdx; c < ctxEnd; c++) {
				contextAfter.add(currLines.get(c));
			}

			SmaliHunk.HunkType hType;
			if (origHunkLines.isEmpty()) {
				hType = SmaliHunk.HunkType.INSERT;
			} else if (modHunkLines.isEmpty()) {
				hType = SmaliHunk.HunkType.DELETE;
			} else {
				hType = SmaliHunk.HunkType.REPLACE;
			}

			int absStart = curr.getStartLineInDocument() + firstCurrLineInMethod;
			int absEnd = absStart + Math.max(0, modHunkLines.size() - 1);
			double relPos = (double) firstCurrLineInMethod / totalCurrLines;

			hunks.add(new SmaliHunk(hunkIdx++, methodSig, hType,
					origHunkLines, modHunkLines,
					contextBefore, contextAfter,
					absStart, absEnd, relPos));
		}

		return hunks;
	}

	private enum OpType { EQUAL, INSERT, DELETE }

	private static class DiffOp {
		final OpType type;
		final String line;
		final int currLineIdx;

		DiffOp(OpType type, String line, int currLineIdx) {
			this.type = type;
			this.line = line;
			this.currLineIdx = currLineIdx;
		}
	}

	/**
	 * Standard Longest Common Subsequence (LCS) line diff.
	 */
	private static List<DiffOp> computeLineDiff(List<String> a, List<String> b) {
		int n = a.size();
		int m = b.size();
		int[][] dp = new int[n + 1][m + 1];

		for (int i = 0; i < n; i++) {
			for (int j = 0; j < m; j++) {
				if (Objects.equals(a.get(i), b.get(j))) {
					dp[i + 1][j + 1] = dp[i][j] + 1;
				} else {
					dp[i + 1][j + 1] = Math.max(dp[i + 1][j], dp[i][j + 1]);
				}
			}
		}

		List<DiffOp> ops = new ArrayList<>();
		int i = n;
		int j = m;

		while (i > 0 || j > 0) {
			if (i > 0 && j > 0 && Objects.equals(a.get(i - 1), b.get(j - 1))) {
				ops.add(new DiffOp(OpType.EQUAL, a.get(i - 1), j - 1));
				i--;
				j--;
			} else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
				ops.add(new DiffOp(OpType.INSERT, b.get(j - 1), j - 1));
				j--;
			} else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
				ops.add(new DiffOp(OpType.DELETE, a.get(i - 1), j));
				i--;
			}
		}

		Collections.reverse(ops);
		return ops;
	}

	public static class RevertResult {
		private final boolean success;
		private final String newSmali;
		private final String errorMessage;
		private final boolean ambiguous;

		public static RevertResult ok(String newSmali) {
			return new RevertResult(true, newSmali, null, false);
		}

		public static RevertResult conflict(String errorMessage) {
			return new RevertResult(false, null, errorMessage, false);
		}

		public static RevertResult ambiguous(String errorMessage) {
			return new RevertResult(false, null, errorMessage, true);
		}

		private RevertResult(boolean success, String newSmali, String errorMessage, boolean ambiguous) {
			this.success = success;
			this.newSmali = newSmali;
			this.errorMessage = errorMessage;
			this.ambiguous = ambiguous;
		}

		public boolean isSuccess() {
			return success;
		}

		public String getNewSmali() {
			return newSmali;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public boolean isAmbiguous() {
			return ambiguous;
		}
	}

	public static RevertResult applyHunkReversion(String currentSmali, SmaliHunk hunk) {
		if (currentSmali == null) {
			return RevertResult.conflict("Current Smali code is empty.");
		}
		String normSmali = currentSmali.replace("\r\n", "\n").replace('\r', '\n');
		String methodSig = hunk.getMethodSignature();

		int methodStart = findMethodStart(normSmali, methodSig);
		if (methodStart == -1) {
			return RevertResult.conflict("Cannot safely revert Change #" + hunk.getHunkIndex()
					+ ":\nMethod " + methodSig + " was not found in current code.\n\nNo changes were made.");
		}
		int methodEnd = normSmali.indexOf(".end method", methodStart);
		if (methodEnd == -1) {
			return RevertResult.conflict("Cannot safely revert Change #" + hunk.getHunkIndex()
					+ ":\nMalformed method block in current code.\n\nNo changes were made.");
		}
		methodEnd += ".end method".length();

		String methodCode = normSmali.substring(methodStart, methodEnd);
		List<String> methodLines = new ArrayList<>(java.util.Arrays.asList(methodCode.split("\n", -1)));

		List<Integer> candidateIndices = findCandidateHunkIndices(methodLines, hunk);
		if (candidateIndices.isEmpty()) {
			return RevertResult.conflict("Cannot safely revert Change #" + hunk.getHunkIndex()
					+ ":\nThe current code no longer matches the expected modified state.\n\nNo changes were made.");
		}

		int chosenIndex = -1;
		if (candidateIndices.size() == 1) {
			chosenIndex = candidateIndices.get(0);
		} else {
			// Disambiguation tie-breaker using relativePositionHint ONLY
			double targetRelative = hunk.getRelativePositionHint();
			double tolerance = 0.10; // Must be within 10% of expected location in method
			List<Integer> plausibleCandidates = new ArrayList<>();
			for (int idx : candidateIndices) {
				double rel = (double) idx / Math.max(1, methodLines.size());
				if (Math.abs(rel - targetRelative) <= tolerance) {
					plausibleCandidates.add(idx);
				}
			}
			if (plausibleCandidates.size() == 1) {
				chosenIndex = plausibleCandidates.get(0);
			} else {
				// 0 plausible candidates (drifted too far) or 2+ candidates (ambiguous) -> CONFLICT
				return RevertResult.ambiguous("Cannot safely revert Change #" + hunk.getHunkIndex()
						+ ":\nMultiple ambiguous locations (" + candidateIndices.size() + ") match this pattern.\nPlease edit manually.\n\nNo changes were made.");
			}
		}

		// Pre-validation succeeded: apply mutation
		int modCount = hunk.getModifiedLines().size();
		for (int i = 0; i < modCount; i++) {
			methodLines.remove(chosenIndex);
		}
		for (int i = 0; i < hunk.getOriginalLines().size(); i++) {
			methodLines.add(chosenIndex + i, hunk.getOriginalLines().get(i));
		}

		String newMethodCode = String.join("\n", methodLines);
		String newSmali = normSmali.substring(0, methodStart) + newMethodCode + normSmali.substring(methodEnd);
		return RevertResult.ok(newSmali);
	}

	public static List<Integer> findCandidateHunkIndices(List<String> methodLines, SmaliHunk hunk) {
		List<Integer> candidates = new ArrayList<>();
		List<String> ctxBefore = hunk.getContextBefore();
		List<String> ctxAfter = hunk.getContextAfter();
		List<String> modLines = hunk.getModifiedLines();

		int ctxBeforeLen = ctxBefore.size();
		int modLen = modLines.size();
		int ctxAfterLen = ctxAfter.size();

		for (int i = 0; i <= methodLines.size() - modLen; i++) {
			if (ctxBeforeLen > 0) {
				if (i < ctxBeforeLen) {
					continue;
				}
				boolean ctxBeforeMatch = true;
				for (int b = 0; b < ctxBeforeLen; b++) {
					if (!normalizeLine(methodLines.get(i - ctxBeforeLen + b)).equals(normalizeLine(ctxBefore.get(b)))) {
						ctxBeforeMatch = false;
						break;
					}
				}
				if (!ctxBeforeMatch) {
					continue;
				}
			}

			boolean modMatch = true;
			for (int m = 0; m < modLen; m++) {
				if (!normalizeLine(methodLines.get(i + m)).equals(normalizeLine(modLines.get(m)))) {
					modMatch = false;
					break;
				}
			}
			if (!modMatch) {
				continue;
			}

			if (ctxAfterLen > 0) {
				if (i + modLen + ctxAfterLen > methodLines.size()) {
					continue;
				}
				boolean ctxAfterMatch = true;
				for (int a = 0; a < ctxAfterLen; a++) {
					if (!normalizeLine(methodLines.get(i + modLen + a)).equals(normalizeLine(ctxAfter.get(a)))) {
						ctxAfterMatch = false;
						break;
					}
				}
				if (!ctxAfterMatch) {
					continue;
				}
			}

			candidates.add(i);
		}

		return candidates;
	}

	public static RevertResult applyMethodReversion(String currentSmali, SmaliMethodChange change) {
		if (currentSmali == null) {
			return RevertResult.conflict("Current Smali code is empty.");
		}
		String normSmali = currentSmali.replace("\r\n", "\n").replace('\r', '\n');
		String methodSig = change.getMethodSignature();

		if (change.isAddedMethod()) {
			int start = findMethodStart(normSmali, methodSig);
			if (start != -1) {
				int end = normSmali.indexOf(".end method", start);
				if (end != -1) {
					end += ".end method".length();
					if (end < normSmali.length() && normSmali.charAt(end) == '\n') {
						end++;
					}
					String newSmali = normSmali.substring(0, start) + normSmali.substring(end);
					return RevertResult.ok(newSmali);
				}
			}
			return RevertResult.conflict("Cannot locate added method " + methodSig + " to remove.");
		}

		if (change.isDeletedMethod()) {
			String beforeCode = change.getBeforeMethodCode();
			if (!beforeCode.isEmpty()) {
				String newSmali = normSmali.trim() + "\n\n" + beforeCode.trim() + "\n";
				return RevertResult.ok(newSmali);
			}
			return RevertResult.conflict("Original code for deleted method " + methodSig + " is empty.");
		}

		int start = findMethodStart(normSmali, methodSig);
		if (start == -1) {
			return RevertResult.conflict("Method " + methodSig + " was not found in current code.");
		}
		int end = normSmali.indexOf(".end method", start);
		if (end == -1) {
			return RevertResult.conflict("Malformed method block for " + methodSig + ".");
		}
		end += ".end method".length();
		String newSmali = normSmali.substring(0, start) + change.getBeforeMethodCode().trim() + normSmali.substring(end);
		return RevertResult.ok(newSmali);
	}

	public static int findMethodStart(String smaliCode, String methodSig) {
		int idx = 0;
		while ((idx = smaliCode.indexOf(".method ", idx)) != -1) {
			int lineEnd = smaliCode.indexOf('\n', idx);
			String line = lineEnd != -1 ? smaliCode.substring(idx, lineEnd).trim() : smaliCode.substring(idx).trim();
			if (parseMethodSignature(line).equals(methodSig)) {
				return idx;
			}
			idx += ".method ".length();
		}
		return -1;
	}

	public static String normalizeLine(String line) {
		return line == null ? "" : line.trim();
	}

	public static String formatHumanReadableSignature(String smaliSig) {
		if (smaliSig == null || smaliSig.isEmpty()) {
			return "";
		}
		int openParen = smaliSig.indexOf('(');
		int closeParen = smaliSig.indexOf(')');
		if (openParen == -1 || closeParen == -1 || closeParen < openParen) {
			int colon = smaliSig.indexOf(':');
			if (colon != -1) {
				String fieldName = smaliSig.substring(0, colon).trim();
				String fieldType = parseSmaliSingleType(smaliSig.substring(colon + 1).trim());
				return fieldName + " : " + fieldType;
			}
			return smaliSig;
		}

		String name = smaliSig.substring(0, openParen).trim();
		String paramsDesc = smaliSig.substring(openParen + 1, closeParen);
		String returnDesc = smaliSig.substring(closeParen + 1).trim();

		List<String> params = parseSmaliTypeList(paramsDesc);
		String returnType = parseSmaliSingleType(returnDesc);

		StringBuilder sb = new StringBuilder();
		sb.append(name).append('(');
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(params.get(i));
		}
		sb.append(')');
		if (!returnType.isEmpty() && !returnType.equals("void")) {
			sb.append(" : ").append(returnType);
		}
		return sb.toString();
	}

	private static List<String> parseSmaliTypeList(String desc) {
		List<String> types = new ArrayList<>();
		if (desc == null || desc.isEmpty()) {
			return types;
		}
		int i = 0;
		while (i < desc.length()) {
			int arrayDim = 0;
			while (i < desc.length() && desc.charAt(i) == '[') {
				arrayDim++;
				i++;
			}
			if (i >= desc.length()) {
				break;
			}
			char c = desc.charAt(i);
			String baseType;
			if (c == 'L') {
				int semi = desc.indexOf(';', i);
				if (semi == -1) {
					semi = desc.length();
				}
				String classPath = desc.substring(i + 1, semi);
				int lastSlash = classPath.lastIndexOf('/');
				baseType = (lastSlash != -1) ? classPath.substring(lastSlash + 1) : classPath;
				int dollar = baseType.lastIndexOf('$');
				if (dollar != -1) {
					baseType = baseType.substring(dollar + 1);
				}
				i = semi + 1;
			} else {
				baseType = decodePrimitive(c);
				i++;
			}
			StringBuilder arraySuffix = new StringBuilder();
			for (int a = 0; a < arrayDim; a++) {
				arraySuffix.append("[]");
			}
			types.add(baseType + arraySuffix);
		}
		return types;
	}

	private static String parseSmaliSingleType(String desc) {
		List<String> list = parseSmaliTypeList(desc);
		return list.isEmpty() ? desc : list.get(0);
	}

	private static String decodePrimitive(char c) {
		switch (c) {
			case 'V': return "void";
			case 'Z': return "boolean";
			case 'B': return "byte";
			case 'S': return "short";
			case 'C': return "char";
			case 'I': return "int";
			case 'J': return "long";
			case 'F': return "float";
			case 'D': return "double";
			default: return String.valueOf(c);
		}
	}

	public static String formatHunkSummary(SmaliHunk hunk) {
		int del = hunk.getOriginalLines().size();
		int add = hunk.getModifiedLines().size();
		switch (hunk.getHunkType()) {
			case REPLACE:
				if (del == add) {
					return del == 1 ? "1 line modified" : del + " lines modified";
				}
				return String.format("-%d, +%d lines", del, add);
			case INSERT:
				return add == 1 ? "1 line inserted" : add + " lines inserted";
			case DELETE:
				return del == 1 ? "1 line deleted" : del + " lines deleted";
			default:
				return hunk.getHunkType().name();
		}
	}
}
