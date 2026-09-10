package jadx.gui.ui.codearea.sync;

import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Use Debug lines in smali from dex debug info to correlate with code
 */
public class DebugLineSmaliSyncer implements IToJavaSyncStrategy {
	private static final Logger LOG = LoggerFactory.getLogger(DebugLineSmaliSyncer.class);

	private final SmaliArea from;

	public DebugLineSmaliSyncer(SmaliArea area) {
		this.from = area;
	}

	@Override
	public boolean syncTo(CodeArea to) {
		try {
			int lineIndex = from.getCaretLineNumber();
			String[] fromLines = from.getText().split("\\R");
			if (lineIndex >= fromLines.length) {
				return false;
			}

			// Find enclosing method and class in Smali
			String methodSig = null;
			int smaliMthStartLine = -1;
			String currentClsSmali = null;

			for (int i = lineIndex; i >= 0; i--) {
				String trimmed = fromLines[i].trim();
				if (methodSig == null && trimmed.startsWith(".end method")) {
					// Caret is between methods (outside any method)
					break;
				}
				if (methodSig == null && trimmed.startsWith(".method")) {
					String[] tokens = trimmed.split("\\s+");
					if (tokens.length > 1) {
						methodSig = tokens[tokens.length - 1];
						smaliMthStartLine = i;
					}
				}
				if (trimmed.startsWith(".class")) {
					String[] tokens = trimmed.split("\\s+");
					if (tokens.length > 1) {
						currentClsSmali = tokens[tokens.length - 1];
					}
					break;
				}
			}

			String className = smaliClsToRawName(currentClsSmali);

			if (methodSig != null) {
				MethodDecl javaMth = findMethodInCodeArea(to, className, methodSig);
				if (javaMth != null) {
					// Check if there is a .line directive between smaliMthStartLine and lineIndex
					Integer sourceLine = null;
					for (int i = lineIndex; i >= smaliMthStartLine; i--) {
						String trimmed = fromLines[i].trim();
						if (trimmed.startsWith(".line")) {
							String[] parts = trimmed.split("\\s+");
							if (parts.length > 1) {
								try {
									sourceLine = Integer.parseInt(parts[1]);
									break;
								} catch (NumberFormatException ignored) {
								}
							}
						}
					}

					int targetJavaLine = -1;
					if (sourceLine != null) {
						Map<Integer, Integer> lineMapping = to.getFunctionUniqueLineMappings();
						if (lineMapping.isEmpty()) {
							lineMapping = to.getLineMappings();
						}
						for (Map.Entry<Integer, Integer> entry : lineMapping.entrySet()) {
							int decompLine = entry.getKey();
							int src = entry.getValue();
							if (src == sourceLine && decompLine >= javaMth.startLine && decompLine <= javaMth.endLine) {
								targetJavaLine = decompLine - 1; // 0-indexed for highlight
								break;
							}
						}
					}

					if (targetJavaLine == -1) {
						targetJavaLine = javaMth.startLine - 1; // 0-indexed
					}

					CodeSyncHighlighter.defaultHighlighter().highlightAndScrollToLine(to, targetJavaLine);
					LOG.info("{} - successful sync of smali to code", LOG.getName());
					return true;
				}
			}

			// Fallback: use legacy anchor if outside method or method not found
			Anchor anchor = findNearestAnchor(lineIndex, fromLines);
			if (anchor == null) {
				LOG.debug("{} - No Smali Anchor found for line {}", LOG.getName(), lineIndex);
				return false;
			}

			if (anchor.getType() == Anchor.Type.SOURCE_LINE) {
				Map<Integer, Integer> toDecompToSourceMapping = to.getFunctionUniqueLineMappings();
				if (toDecompToSourceMapping.isEmpty()) {
					toDecompToSourceMapping = to.getLineMappings();
				}
				for (Map.Entry<Integer, Integer> entry : toDecompToSourceMapping.entrySet()) {
					int decompLine = entry.getKey();
					int sourceLine = entry.getValue();
					if (anchor.getCodeMappedLineNumber() == sourceLine) {
						int decompLineIndex = decompLine - 1;
						CodeSyncHighlighter.defaultHighlighter().highlightAndScrollToLine(to, decompLineIndex);
						LOG.info("{} - successful sync of smali to code", LOG.getName());
						return true;
					}
				}
			}
			to.removeAllLineHighlights();
		} catch (Exception ex) {
			LOG.error("{} - Failed to sync from Smali to Code", LOG.getName(), ex);
		}
		return false;
	}

	private static class MethodDecl {
		final int defPos;
		final int startLine; // 1-indexed
		final int endLine;   // 1-indexed

		MethodDecl(int defPos, int startLine, int endLine) {
			this.defPos = defPos;
			this.startLine = startLine;
			this.endLine = endLine;
		}
	}

	private static @Nullable MethodDecl findMethodInCodeArea(CodeArea to, @Nullable String className, String methodSig) {
		ICodeMetadata metadata = to.getCodeMetadata();
		if (metadata == null) {
			return null;
		}
		MethodDecl fallbackMatch = null;
		for (Map.Entry<Integer, ICodeAnnotation> entry : metadata.getAsMap().entrySet()) {
			ICodeAnnotation ann = entry.getValue();
			if (ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
				NodeDeclareRef decl = (NodeDeclareRef) ann;
				if (decl.getNode().getAnnType() == ICodeAnnotation.AnnType.METHOD) {
					MethodNode mth = (MethodNode) decl.getNode();
					String rawFullId = mth.getMethodInfo().getRawFullId();
					int defPos = entry.getKey();
					int startLine = 1;
					try {
						startLine = to.getLineOfOffset(defPos) + 1;
					} catch (Exception ignored) {
					}
					int endLine = findMethodEndLine(to, metadata, defPos);

					if (matchesMethod(rawFullId, className, methodSig)) {
						return new MethodDecl(defPos, startLine, endLine);
					}
					if (matchesMethodSigOnly(rawFullId, methodSig) && fallbackMatch == null) {
						fallbackMatch = new MethodDecl(defPos, startLine, endLine);
					}
				}
			}
		}
		return fallbackMatch;
	}

	private static boolean matchesMethod(String rawFullId, @Nullable String className, String methodSig) {
		int paren = rawFullId.indexOf('(');
		if (paren <= 0) {
			return false;
		}
		int dot = rawFullId.lastIndexOf('.', paren - 1);
		if (dot <= 0) {
			return rawFullId.equals(methodSig);
		}
		String cls = rawFullId.substring(0, dot);
		String sig = rawFullId.substring(dot + 1);
		if (!sig.equals(methodSig)) {
			return false;
		}
		if (className == null) {
			return true;
		}
		return cls.equals(className) || cls.endsWith("." + className) || className.endsWith("." + cls);
	}

	private static boolean matchesMethodSigOnly(String rawFullId, String methodSig) {
		int paren = rawFullId.indexOf('(');
		if (paren <= 0) {
			return false;
		}
		int dot = rawFullId.lastIndexOf('.', paren - 1);
		String sig = dot > 0 ? rawFullId.substring(dot + 1) : rawFullId;
		return sig.equals(methodSig);
	}

	private static int findMethodEndLine(CodeArea area, ICodeMetadata metadata, int defPos) {
		try {
			Map.Entry<Integer, ICodeAnnotation> mthEnd = metadata.searchDown(defPos, (offset, ann) -> {
				if (ann.getAnnType() != ICodeAnnotation.AnnType.END) {
					return null;
				}
				return new SimpleEntry<>(offset, ann);
			});
			if (mthEnd != null) {
				return area.getLineOfOffset(mthEnd.getKey()) + 1;
			}
		} catch (Exception ignored) {
		}
		return Integer.MAX_VALUE;
	}

	private static @Nullable String smaliClsToRawName(@Nullable String smaliCls) {
		if (smaliCls == null) {
			return null;
		}
		if (smaliCls.startsWith("L") && smaliCls.endsWith(";")) {
			return smaliCls.substring(1, smaliCls.length() - 1).replace('/', '.');
		}
		return smaliCls;
	}

	private @Nullable Anchor findNearestAnchor(int smaliLineNumber, String[] lines) {
		for (int i = smaliLineNumber; i >= 0; i--) {
			String trimmedLine = lines[i].trim();
			if (trimmedLine.startsWith(".line")) {
				return new Anchor(Anchor.Type.SOURCE_LINE, trimmedLine, i);
			}
			if (trimmedLine.startsWith(".method")) {
				return new Anchor(Anchor.Type.METHOD_START, trimmedLine, i);
			}
			if (trimmedLine.startsWith(".end")) {
				return new Anchor(Anchor.Type.METHOD_END, trimmedLine, i);
			}
			if (trimmedLine.startsWith(".field")) {
				return new Anchor(Anchor.Type.FIELD, trimmedLine, i);
			}
			if (trimmedLine.startsWith(".class")) {
				return new Anchor(Anchor.Type.CLASS, trimmedLine, smaliLineNumber);
			}
		}
		return null;
	}

	/**
	 * Line in the smali that can be used to find a section to highlight in the code area
	 */
	private static class Anchor {
		public enum Type {
			SOURCE_LINE,
			METHOD_START,
			METHOD_END,
			FIELD,
			CLASS
		}

		private final Type type;
		private final String line;
		private final int smaliLineNumber;
		private int codeMappedLineNumber = -1;

		public Anchor(Type type, String line, int smaliLineNumber) {
			this.type = type;
			this.line = line;
			this.smaliLineNumber = smaliLineNumber;
			this.map();
		}

		public Type getType() {
			return type;
		}

		public int getCodeMappedLineNumber() {
			return codeMappedLineNumber;
		}

		private void map() {
			switch (type) {
				case SOURCE_LINE:
					Pattern p = Pattern.compile("(\\.line\\s)(\\d+)");
					Matcher m = p.matcher(line);
					if (m.find()) {
						codeMappedLineNumber = Integer.parseInt(m.group(2));
					}
					break;
				default:
					codeMappedLineNumber = -1;
					break;
			}
		}

		@Override
		public String toString() {
			return String.format("Anchor %s, %d, %d", type.name(), smaliLineNumber, codeMappedLineNumber);
		}
	}
}
