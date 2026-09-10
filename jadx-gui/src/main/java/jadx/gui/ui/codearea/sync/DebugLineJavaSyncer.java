package jadx.gui.ui.codearea.sync;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.dex.nodes.MethodNode;
import jadx.gui.ui.codearea.CodeArea;
import jadx.gui.ui.codearea.SmaliArea;

/**
 * Use debug line info from dex to correlate from java to java/smali
 */
public class DebugLineJavaSyncer implements IToSmaliSyncStrategy, IToJavaSyncStrategy {
	private static final Logger LOG = LoggerFactory.getLogger(DebugLineJavaSyncer.class);

	private final CodeArea from;

	public DebugLineJavaSyncer(CodeArea area) {
		this.from = area;
	}

	@Override
	public boolean syncTo(CodeArea to) {
		// This might be any combination between java/simple/fallback
		// We cannot just rely on the current line.
		// Instead, try to correlate with line mappings.
		try {
			Map<Integer, Integer> toLineMapping = to.getFunctionUniqueLineMappings();
			if (toLineMapping.isEmpty()) {
				return false;
			}
			int lineIndex = from.getCaretLineNumber();
			// lineIndex is 0-indexed whereas the line mappings are based off a 1-index.
			Integer sourceLine = getClosestSourceLine(lineIndex + 1, from.getFunctionUniqueLineMappings());
			if (sourceLine == null) {
				return false;
			}
			// find the equivalent line number in the 'to' by a reverse lookup from the source line
			for (Map.Entry<Integer, Integer> entry : toLineMapping.entrySet()) {
				int toLine = entry.getKey();
				int candidateSourceLine = entry.getValue();
				if (sourceLine == candidateSourceLine) {
					// we have the mapped line we target the lineIndex which is a 0-index
					CodeSyncHighlighter.defaultHighlighter().highlightAndScrollToLine(to, toLine - 1);
					LOG.info("{} - successful sync of code to code", LOG.getName());
					return true;
				}
			}
		} catch (Exception e) {
			LOG.error("{} - Failed to sync from CodeArea to CodeArea: {}", LOG.getName(), e.getLocalizedMessage());
		}
		return false;
	}

	@Override
	public boolean syncTo(SmaliArea to) {
		try {
			int lineIndex = from.getCaretLineNumber();
			int lineNum = lineIndex + 1; // 1-indexed

			MethodScope scope = getEnclosingMethodScope(from.getCaretPosition());
			String[] smaliLines = to.getText().split("\\R");

			if (scope != null) {
				SmaliMethodRange smaliMthRange = findSmaliMethodRange(smaliLines, scope.className, scope.methodSig);
				if (smaliMthRange != null) {
					int targetSmaliLine = smaliMthRange.headerLine;

					// If caret is inside the method body (past the declaration line), try to find matching .line in method
					if (lineNum > scope.startLine) {
						Integer sourceLine = getClosestSourceLineInMethod(lineNum, scope.startLine);
						if (sourceLine != null) {
							String targetDirective = ".line " + sourceLine;
							for (int i = smaliMthRange.headerLine; i <= smaliMthRange.endLine; i++) {
								if (smaliLines[i].trim().equals(targetDirective)) {
									targetSmaliLine = i;
									break;
								}
							}
						}
					}

					CodeSyncHighlighter.defaultHighlighter().highlightAndScrollToLine(to, targetSmaliLine);
					LOG.info("{} - successful sync of code to smali", LOG.getName());
					return true;
				}
			}

			// Fallback: caret outside any method (class header, fields, etc.)
			Integer sourceLine = getClosestSourceLine(lineNum, from.getFunctionUniqueLineMappings());
			if (sourceLine == null) {
				sourceLine = getClosestSourceLine(lineNum, from.getLineMappings());
			}
			if (sourceLine != null) {
				String targetDirective = ".line " + sourceLine;
				List<Integer> candidates = new ArrayList<>();
				for (int i = 0; i < smaliLines.length; i++) {
					if (smaliLines[i].trim().equals(targetDirective)) {
						candidates.add(i);
					}
				}
				if (candidates.size() == 1) {
					CodeSyncHighlighter.defaultHighlighter().highlightAndScrollToLine(to, candidates.get(0));
					LOG.info("{} - successful sync of code to smali", LOG.getName());
					return true;
				}
			}

			to.removeAllLineHighlights();
			return false;
		} catch (Exception ex) {
			LOG.error("{} - Failed to sync CodeArea to SmaliArea: {}", LOG.getName(), ex.getLocalizedMessage());
		}
		return false;
	}

	private static class MethodScope {
		final String rawId;
		final @Nullable String className;
		final String methodSig;
		final int startLine; // 1-indexed
		final int endLine;   // 1-indexed

		MethodScope(String rawId, @Nullable String className, String methodSig, int startLine, int endLine) {
			this.rawId = rawId;
			this.className = className;
			this.methodSig = methodSig;
			this.startLine = startLine;
			this.endLine = endLine;
		}
	}

	private static class SmaliMethodRange {
		final int headerLine;
		final int endLine;

		SmaliMethodRange(int headerLine, int endLine) {
			this.headerLine = headerLine;
			this.endLine = endLine;
		}
	}

	private @Nullable MethodScope getEnclosingMethodScope(int caretPos) {
		ICodeMetadata metadata = from.getCodeMetadata();
		if (metadata == null) {
			return null;
		}

		// Direct declaration at caret
		ICodeAnnotation atCaret = metadata.getAt(caretPos);
		if (atCaret != null && atCaret.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
			NodeDeclareRef declAtCaret = (NodeDeclareRef) atCaret;
			if (declAtCaret.getNode().getAnnType() == ICodeAnnotation.AnnType.METHOD) {
				MethodNode mth = (MethodNode) declAtCaret.getNode();
				return createMethodScope(mth, caretPos, metadata);
			}
		}

		// Search up for enclosing method declaration
		Map.Entry<Integer, ICodeAnnotation> mthDef = metadata.searchUp(caretPos, (offset, ann) -> {
			if (ann.getAnnType() != ICodeAnnotation.AnnType.DECLARATION) {
				return null;
			}
			NodeDeclareRef decl = (NodeDeclareRef) ann;
			if (decl.getNode().getAnnType() != ICodeAnnotation.AnnType.METHOD) {
				return null;
			}
			return new SimpleEntry<>(offset, ann);
		});
		if (mthDef != null) {
			NodeDeclareRef ref = (NodeDeclareRef) mthDef.getValue();
			MethodNode mth = (MethodNode) ref.getNode();
			return createMethodScope(mth, mthDef.getKey(), metadata);
		}

		return null;
	}

	private @Nullable MethodScope createMethodScope(MethodNode mth, int defPos, ICodeMetadata metadata) {
		String rawId = mth.getMethodInfo().getRawFullId();
		int paren = rawId.indexOf('(');
		if (paren <= 0) {
			return null;
		}
		int dot = rawId.lastIndexOf('.', paren - 1);
		String className = dot > 0 ? rawId.substring(0, dot) : null;
		String methodSig = dot > 0 ? rawId.substring(dot + 1) : rawId;

		int startLine = 1;
		try {
			startLine = from.getLineOfOffset(defPos) + 1;
		} catch (Exception ignored) {
		}

		int endLine = Integer.MAX_VALUE;
		try {
			Map.Entry<Integer, ICodeAnnotation> mthEnd = metadata.searchDown(defPos, (offset, ann) -> {
				if (ann.getAnnType() != ICodeAnnotation.AnnType.END) {
					return null;
				}
				return new SimpleEntry<>(offset, ann);
			});
			if (mthEnd != null) {
				endLine = from.getLineOfOffset(mthEnd.getKey()) + 1;
			}
		} catch (Exception ignored) {
		}

		return new MethodScope(rawId, className, methodSig, startLine, endLine);
	}

	private @Nullable Integer getClosestSourceLineInMethod(int lineNum, int mthStartLine) {
		Map<Integer, Integer> uniqueMappings = from.getFunctionUniqueLineMappings();
		for (int cur = lineNum; cur >= mthStartLine; cur--) {
			Integer sourceLine = uniqueMappings.get(cur);
			if (sourceLine != null) {
				return sourceLine;
			}
		}
		Map<Integer, Integer> lineMappings = from.getLineMappings();
		for (int cur = lineNum; cur >= mthStartLine; cur--) {
			Integer sourceLine = lineMappings.get(cur);
			if (sourceLine != null) {
				return sourceLine;
			}
		}
		return null;
	}

	private static @Nullable SmaliMethodRange findSmaliMethodRange(String[] smaliLines, @Nullable String className, String methodSig) {
		String targetClsSmali = className != null ? "L" + className.replace('.', '/') + ";" : null;
		String currentCls = null;
		int headerLine = -1;

		for (int i = 0; i < smaliLines.length; i++) {
			String trimmed = smaliLines[i].trim();
			if (trimmed.startsWith(".class")) {
				String[] tokens = trimmed.split("\\s+");
				currentCls = tokens[tokens.length - 1];
			} else if (trimmed.startsWith(".method")) {
				String[] tokens = trimmed.split("\\s+");
				if (tokens.length > 1 && tokens[tokens.length - 1].equals(methodSig)) {
					if (targetClsSmali == null || currentCls == null || targetClsSmali.equals(currentCls)) {
						headerLine = i;
						break;
					}
				}
			}
		}

		// Relax class match if not found
		if (headerLine == -1 && targetClsSmali != null) {
			for (int i = 0; i < smaliLines.length; i++) {
				String trimmed = smaliLines[i].trim();
				if (trimmed.startsWith(".method")) {
					String[] tokens = trimmed.split("\\s+");
					if (tokens.length > 1 && tokens[tokens.length - 1].equals(methodSig)) {
						headerLine = i;
						break;
					}
				}
			}
		}

		if (headerLine == -1) {
			return null;
		}

		int endLine = smaliLines.length - 1;
		for (int i = headerLine + 1; i < smaliLines.length; i++) {
			if (smaliLines[i].trim().startsWith(".end method")) {
				endLine = i;
				break;
			}
		}

		return new SmaliMethodRange(headerLine, endLine);
	}

	private @Nullable Integer getClosestSourceLine(int lineNum, Map<Integer, Integer> lineMapping) {
		if (lineMapping.isEmpty()) {
			return null;
		}
		Integer sourceLine = null;
		int cur = lineNum;
		while (cur >= 0 && (sourceLine = lineMapping.get(cur)) == null) {
			--cur;
		}
		return sourceLine;
	}
}

