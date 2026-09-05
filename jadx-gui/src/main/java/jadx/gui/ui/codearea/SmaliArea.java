package jadx.gui.ui.codearea;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.EditorKit;
import javax.swing.text.JTextComponent;

import org.fife.ui.rsyntaxtextarea.FoldingAwareIconRowHeader;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaUI;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.IconRowHeader;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextAreaUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.ICodeInfo;
import jadx.api.plugins.input.ICodeLoader;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.gui.device.debugger.BreakpointManager;
import jadx.gui.device.debugger.DbgUtils;
import jadx.gui.jobs.IBackgroundTask;
import jadx.gui.jobs.LoadTask;
import jadx.gui.patching.ModifiedDexManager;
import jadx.gui.patching.history.PatchCommit;
import jadx.gui.patching.history.PatchDiffDialog;
import jadx.gui.patching.history.PatchHistoryDialog;
import jadx.gui.patching.history.PatchHistoryManager;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.TextNode;
import jadx.gui.ui.codearea.sync.CodeAreaSyncee;
import jadx.gui.ui.codearea.sync.CodeAreaSyncer;
import jadx.gui.ui.codearea.sync.CodeAreaSyncerAbstractFactory;
import jadx.gui.ui.codearea.sync.SmaliSyncer;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.utils.UiUtils;
import jadx.plugins.input.dex.DexInputPlugin;
import jadx.plugins.input.smali.SmaliUtils;

public final class SmaliArea extends AbstractCodeArea implements CodeAreaSyncerAbstractFactory, CodeAreaSyncee {
	private static final Logger LOG = LoggerFactory.getLogger(SmaliArea.class);

	private static final long serialVersionUID = 1334485631870306494L;

	private static final Icon ICON_BREAKPOINT = UiUtils.openSvgIcon("debugger/db_set_breakpoint");
	private static final Icon ICON_BREAKPOINT_DISABLED = UiUtils.openSvgIcon("debugger/db_disabled_breakpoint");
	private static final Color BREAKPOINT_LINE_COLOR = Color.decode("#ad103c");
	private static final Color DEBUG_LINE_COLOR = Color.decode("#9c1138");

	private final JNode textNode;
	private final SmaliModel model;

	SmaliArea(ContentPanel contentPanel, JClass node, boolean showBytecode) {
		super(contentPanel, node);
		setCodeFoldingEnabled(true);
		this.textNode = new TextNode(node.getName());
		this.model = showBytecode ? new DebugModel() : new NormalModel(this);
		setEditable(!showBytecode);
		if (!showBytecode) {
			KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, UiUtils.ctrlButton());
			UiUtils.addKeyBinding(this, saveKey, "ApplySmaliAction", new AbstractAction() {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					applySmali();
				}
			});

			KeyStroke rollbackKey = KeyStroke.getKeyStroke(KeyEvent.VK_Z, UiUtils.ctrlButton() | KeyEvent.ALT_DOWN_MASK);
			UiUtils.addKeyBinding(this, rollbackKey, "RollbackSmaliAction", new AbstractAction() {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					rollbackPreviousEdit();
				}
			});
		}
		setUnLoaded();
		load();
	}

	@Override
	protected JPopupMenu createPopupMenu() {
		JPopupMenu popup = super.createPopupMenu();
		if (!isShowingDalvikBytecode()) {
			JMenuItem applyItem = new JMenuItem("Apply Smali Changes");
			applyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, UiUtils.ctrlButton()));
			applyItem.addActionListener(e -> applySmali());
			popup.add(applyItem, 0);

			JMenuItem rollbackItem = new JMenuItem("⏪ Rollback to Previous Edit");
			rollbackItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, UiUtils.ctrlButton() | KeyEvent.ALT_DOWN_MASK));
			rollbackItem.addActionListener(e -> rollbackPreviousEdit());
			popup.add(rollbackItem, 1);

			JMenuItem diffPrevItem = new JMenuItem("🔍 Compare with Previous Edit");
			diffPrevItem.addActionListener(e -> showDiffWithPrevious());
			popup.add(diffPrevItem, 2);

			JMenuItem diffOrigItem = new JMenuItem("🔍 Compare with Original APK");
			diffOrigItem.addActionListener(e -> showDiffWithOriginal());
			popup.add(diffOrigItem, 3);

			JMenuItem revertItem = new JMenuItem("🔄 Revert to Original APK State");
			revertItem.addActionListener(e -> revertToBaseline());
			popup.add(revertItem, 4);

			JMenuItem historyItem = new JMenuItem("📜 Patch Timeline & History...");
			historyItem.addActionListener(e -> showPatchHistory());
			popup.add(historyItem, 5);

			popup.add(new JPopupMenu.Separator(), 6);
		}
		return popup;
	}

	@Override
	public IBackgroundTask getLoadTask() {
		return new LoadTask<>(
				model::loadCode,
				code -> {
					model.loadUI(code);
					setCaretPosition(0);
					setLoaded();
					if (!isShowingDalvikBytecode() && code != null && getJClass() != null) {
						PatchHistoryManager.getInstance().recordBaseline(getJClass().getFullName(), code);
					}
				});
	}

	@Override
	public ICodeInfo getCodeInfo() {
		return ICodeInfo.EMPTY;
	}

	@Override
	public void refresh() {
		setUnLoaded();
		load();
	}

	@Override
	public JNode getNode() {
		// this area contains only smali without other node attributes
		return textNode;
	}

	public boolean isShowingDalvikBytecode() {
		return model instanceof DebugModel;
	}

	public JClass getJClass() {
		return (JClass) node;
	}

	public void scrollToDebugPos(int pos) {
		model.togglePosHighlight(pos);
	}

	public boolean applySmali() {
		if (isShowingDalvikBytecode()) {
			JOptionPane.showMessageDialog(this, "Dalvik bytecode view is read-only.", "Apply Smali", JOptionPane.WARNING_MESSAGE);
			return false;
		}
		return applySmaliCode(getText(), null);
	}

	public boolean applySmaliCode(String smaliCode, @org.jetbrains.annotations.Nullable String commitMsg) {
		if (smaliCode == null || smaliCode.trim().isEmpty()) {
			JOptionPane.showMessageDialog(this, "Smali code is empty.", "Apply Smali", JOptionPane.WARNING_MESSAGE);
			return false;
		}
		try {
			byte[] dexBytes = SmaliUtils.assemble(smaliCode);
			if (dexBytes == null || dexBytes.length == 0) {
				throw new JadxRuntimeException("Assembled DEX bytes are empty");
			}
			String classFullName = getJClass().getFullName();
			LOG.info("Smali assembled successfully for class {}, size: {} bytes", classFullName, dexBytes.length);

			// Phase 2: Inject modified bytecode into JADX decompilation context and trigger re-decompilation
			ClassNode targetClassNode = getJClass().getCls().getClassNode();
			RootNode rootNode = targetClassNode.root();
			String targetDexName = targetClassNode.getInputFileName();
			if (targetDexName == null || targetDexName.equals("memory.dex") || targetDexName.isEmpty()) {
				targetDexName = "classes.dex";
			}

			DexInputPlugin dexInput = new DexInputPlugin();
			ICodeLoader codeLoader = dexInput.loadDex(dexBytes, targetDexName);

			List<ClassNode> reloadedTopClasses = new ArrayList<>();
			String finalTargetDexName = targetDexName;
			String baselineCode = PatchHistoryManager.getInstance().getBaselineSmali(classFullName);
			boolean isBaseline = baselineCode != null && baselineCode.equals(smaliCode);

			codeLoader.visitClasses(newClsData -> {
				String rawType = newClsData.getType();
				ClassNode clsNode = rootNode.resolveClass(ArgType.object(rawType));
				if (clsNode != null) {
					String origDex = clsNode.getInputFileName();
					if (origDex == null || origDex.equals("memory.dex") || origDex.isEmpty()) {
						origDex = finalTargetDexName;
					}
					clsNode.updateClassData(newClsData);
					clsNode.setInputFileName(origDex);
					if (isBaseline) {
						ModifiedDexManager.getInstance().unregisterModifiedClass(rawType);
					} else {
						ModifiedDexManager.getInstance().registerModifiedClass(origDex, rawType, dexBytes);
					}
					ClassNode topParent = clsNode.getTopParentClass();
					if (!reloadedTopClasses.contains(topParent)) {
						reloadedTopClasses.add(topParent);
					}
				}
			});

			for (ClassNode topCls : reloadedTopClasses) {
				DbgUtils.clearSmaliCache(topCls.getClassInfo());
				if (topCls.getJavaNode() != null) {
					topCls.getJavaNode().reload();
				}
			}

			int savedCaret = getCaretPosition();
			JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
			Point savedViewPos = viewport != null ? viewport.getViewPosition() : null;

			if (contentPanel instanceof ClassCodeContentPanel) {
				ClassCodeContentPanel cPanel = (ClassCodeContentPanel) contentPanel;
				cPanel.updateSmaliAreas(smaliCode);
				cPanel.refreshJavaViews();
			}

			String msg = commitMsg != null ? commitMsg : "Modified " + classFullName;
			PatchHistoryManager.getInstance().recordEdit(classFullName, smaliCode, msg);

			SwingUtilities.invokeLater(() -> {
				try {
					if (savedCaret <= getDocument().getLength()) {
						setCaretPosition(savedCaret);
					}
				} catch (Exception ignored) {
				}
				if (viewport != null && savedViewPos != null) {
					viewport.setViewPosition(savedViewPos);
				}
				requestFocusInWindow();
			});

			if (commitMsg != null) {
				UiUtils.showToast(contentPanel.getMainWindow(), "✓ " + commitMsg + " successfully!");
			} else {
				UiUtils.showToast(contentPanel.getMainWindow(),
						"✓ Smali applied & Java updated (" + dexBytes.length + " bytes)");
			}
			return true;
		} catch (Exception e) {
			LOG.error("Failed to assemble smali for class {}", getJClass().getFullName(), e);
			String msg = e.getMessage() != null ? e.getMessage() : e.toString();
			JOptionPane.showMessageDialog(contentPanel.getMainWindow(),
					"Smali Assembly Error:\n" + msg,
					"Apply Smali Failed",
					JOptionPane.ERROR_MESSAGE);
			return false;
		}
	}

	public boolean rollbackPreviousEdit() {
		String classType = getJClass().getFullName();
		String prevCode = PatchHistoryManager.getInstance().getHistoricalSmali(classType, 1);
		if (prevCode == null) {
			JOptionPane.showMessageDialog(this, "No previous checkpoint found for " + classType, "Rollback", JOptionPane.INFORMATION_MESSAGE);
			return false;
		}
		return applySmaliCode(prevCode, "Rollback " + classType);
	}

	public boolean revertToBaseline() {
		String classType = getJClass().getFullName();
		int confirm = JOptionPane.showConfirmDialog(this,
				"Revert " + classType + " back to original APK state?",
				"Revert to Baseline", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return false;
		}
		String baselineCode = PatchHistoryManager.getInstance().getBaselineSmali(classType);
		if (baselineCode == null) {
			JOptionPane.showMessageDialog(this, "No original baseline found for " + classType, "Revert", JOptionPane.INFORMATION_MESSAGE);
			return false;
		}
		return applySmaliCode(baselineCode, "Revert to baseline " + classType);
	}

	public void showDiffWithPrevious() {
		String classType = getJClass().getFullName();
		String prevCode = PatchHistoryManager.getInstance().getHistoricalSmali(classType, 1);
		if (prevCode == null) {
			JOptionPane.showMessageDialog(this, "No previous checkpoint to compare with.", "Diff", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		PatchDiffDialog dlg = new PatchDiffDialog(
				contentPanel.getMainWindow(), classType,
				"Diff: Previous vs Current — " + classType,
				prevCode, "Previous Checkpoint",
				getText(), "Current Active Code",
				this::rollbackPreviousEdit
		);
		dlg.setVisible(true);
	}

	public void showDiffWithOriginal() {
		String classType = getJClass().getFullName();
		String baselineCode = PatchHistoryManager.getInstance().getBaselineSmali(classType);
		if (baselineCode == null) {
			JOptionPane.showMessageDialog(this, "No original baseline to compare with.", "Diff", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		PatchDiffDialog dlg = new PatchDiffDialog(
				contentPanel.getMainWindow(), classType,
				"Diff: Original APK vs Current — " + classType,
				baselineCode, "Original APK Baseline",
				getText(), "Current Active Code",
				this::revertToBaseline
		);
		dlg.setVisible(true);
	}

	public void showPatchHistory() {
		String classType = getJClass().getFullName();
		PatchHistoryDialog dlg = new PatchHistoryDialog(
				contentPanel.getMainWindow(), classType, getText(),
				historicalCode -> {
					applySmaliCode(historicalCode, "Rollback " + classType);
				}
		);
		dlg.setVisible(true);
	}

	@Override
	public Font getFont() {
		if (model == null || isDisposed()) {
			return super.getFont();
		}
		return model.getFont();
	}

	@Override
	public Font getFontForTokenType(int type) {
		return getFont();
	}

	private abstract class SmaliModel {
		abstract String loadCode();

		abstract void loadUI(String code);

		abstract void unload();

		Font getFont() {
			return SmaliArea.super.getFont();
		}

		Font getFontForTokenType(int type) {
			return SmaliArea.super.getFontForTokenType(type);
		}

		void setBreakpoint(int off) {
		}

		void togglePosHighlight(int pos) {
		}
	}

	private class NormalModel extends SmaliModel {
		private NormalModel(SmaliArea smaliArea) {
			getContentPanel().getMainWindow().getEditorThemeManager().apply(smaliArea);
			setSyntaxEditingStyle(SYNTAX_STYLE_SMALI);
		}

		@Override
		public String loadCode() {
			return getJClass().getSmali();
		}

		@Override
		public void loadUI(String code) {
			setText(code);
			setEditable(true);
		}

		@Override
		public void unload() {
		}
	}

	private class DebugModel extends SmaliModel {
		private KeyStroke bpShortcut;
		private Gutter gutter;
		private Object runningHighlightTag = null; // running line
		private final SmaliV2Style smaliV2Style = new SmaliV2Style(SmaliArea.this);
		private final Map<Integer, BreakpointLine> bpMap = new HashMap<>();
		private final PropertyChangeListener schemeListener = evt -> {
			if (smaliV2Style.refreshTheme()) {
				setSyntaxScheme(smaliV2Style);
			}
		};

		private DebugModel() {
			loadV2Style();
			setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_6502);
			addPropertyChangeListener(SYNTAX_SCHEME_PROPERTY, schemeListener);
			regBreakpointEvents();
		}

		@Override
		String loadCode() {
			return DbgUtils.getSmaliCode(((JClass) node).getCls().getClassNode());
		}

		@Override
		void loadUI(String code) {
			if (gutter == null) {
				gutter = RSyntaxUtilities.getGutter(SmaliArea.this);
				gutter.setBookmarkingEnabled(true);
				gutter.setIconRowHeaderInheritsGutterBackground(true);
				Font baseFont = SmaliArea.super.getFont();
				gutter.setLineNumberFont(baseFont.deriveFont(baseFont.getSize2D() - 1.0f));
			}
			setText(code);
			setEditable(false);
			loadV2Style();
			loadBreakpoints();
		}

		@Override
		public void unload() {
			removePropertyChangeListener(schemeListener);
			removeLineHighlight(runningHighlightTag);
			UiUtils.removeKeyBinding(SmaliArea.this, bpShortcut, "set a break point");
			BreakpointManager.removeListener((JClass) node);
			bpMap.forEach((k, v) -> v.remove());
		}

		@Override
		public Font getFont() {
			return smaliV2Style.getFont();
		}

		@Override
		public Font getFontForTokenType(int type) {
			return smaliV2Style.getFont();
		}

		private void loadV2Style() {
			setSyntaxScheme(smaliV2Style);
		}

		private void regBreakpointEvents() {
			bpShortcut = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0);
			UiUtils.addKeyBinding(SmaliArea.this, bpShortcut, "set break point", new AbstractAction() {
				private static final long serialVersionUID = -1111111202103170738L;

				@Override
				public void actionPerformed(ActionEvent e) {
					setBreakpoint(getCaretPosition());
				}
			});
			BreakpointManager.addListener((JClass) node, this::setBreakpointDisabled);
		}

		private void loadBreakpoints() {
			List<Integer> posList = BreakpointManager.getPositions((JClass) node);
			for (Integer integer : posList) {
				setBreakpoint(integer);
			}
		}

		@Override
		public void setBreakpoint(int pos) {
			int line;
			try {
				line = getLineOfOffset(pos);
			} catch (BadLocationException e) {
				LOG.error("Failed to get line by offset: {}", pos, e);
				return;
			}
			BreakpointLine bpLine = bpMap.remove(line);
			if (bpLine == null) {
				bpLine = new BreakpointLine(line);
				bpLine.setDisabled(false);
				bpMap.put(line, bpLine);
				if (!BreakpointManager.set((JClass) node, line)) {
					bpLine.setDisabled(true);
				}
			} else {
				BreakpointManager.remove((JClass) node, line);
				bpLine.remove();
			}
		}

		@Override
		public void togglePosHighlight(int pos) {
			if (runningHighlightTag != null) {
				removeLineHighlight(runningHighlightTag);
			}
			try {
				int line = getLineOfOffset(pos);
				runningHighlightTag = addLineHighlight(line, DEBUG_LINE_COLOR);
			} catch (BadLocationException e) {
				LOG.error("Failed to get line by offset: {}", pos, e);
			}
		}

		private void setBreakpointDisabled(int pos) {
			try {
				int line = getLineOfOffset(pos);
				bpMap.computeIfAbsent(line, k -> new BreakpointLine(line)).setDisabled(true);
			} catch (BadLocationException e) {
				LOG.error("Failed to get line by offset: {}", pos, e);
			}
		}

		private class SmaliV2Style extends SyntaxScheme {
			public SmaliV2Style(SmaliArea smaliArea) {
				super(true);
				smaliArea.getContentPanel().getMainWindow().getEditorThemeManager().apply(smaliArea);
				updateTheme();
			}

			public Font getFont() {
				return getContentPanel().getMainWindow().getSettings().getSmaliFont();
			}

			public boolean refreshTheme() {
				boolean refresh = getSyntaxScheme() != this;
				if (refresh) {
					updateTheme();
				}
				return refresh;
			}

			private void updateTheme() {
				Style[] mainStyles = getSyntaxScheme().getStyles();
				Style[] styles = new Style[mainStyles.length];
				for (int i = 0; i < mainStyles.length; i++) {
					Style mainStyle = mainStyles[i];
					if (mainStyle == null) {
						styles[i] = new Style();
					} else {
						// font will be hijacked by getFont & getFontForTokenType,
						// so it doesn't need to be set here.
						styles[i] = new Style(mainStyle.foreground, mainStyle.background, null);
					}
				}
				setStyles(styles);
			}

			@Override
			public void restoreDefaults(Font baseFont) {
				restoreDefaults(baseFont, true);
			}

			@Override
			public void restoreDefaults(Font baseFont, boolean fontStyles) {
				// Note: it's a hook for continuing using the editor theme, better don't remove it.
			}
		}

		private class BreakpointLine {
			Object highlightTag;
			GutterIconInfo iconInfo;
			boolean disabled;
			final int line;

			BreakpointLine(int line) {
				this.line = line;
				this.disabled = true;
			}

			void remove() {
				safeRemoveTrackingIcon(iconInfo);
				if (!this.disabled) {
					removeLineHighlight(highlightTag);
				}
			}

			void setDisabled(boolean disabled) {
				if (disabled) {
					if (!this.disabled) {
						safeRemoveTrackingIcon(iconInfo);
						removeLineHighlight(highlightTag);
						try {
							iconInfo = gutter.addLineTrackingIcon(line, ICON_BREAKPOINT_DISABLED);
						} catch (BadLocationException e) {
							LOG.error("Failed to add line tracking icon", e);
						}
					}
				} else {
					if (this.disabled) {
						safeRemoveTrackingIcon(this.iconInfo);
						try {
							iconInfo = gutter.addLineTrackingIcon(line, ICON_BREAKPOINT);
							highlightTag = addLineHighlight(line, BREAKPOINT_LINE_COLOR);
						} catch (BadLocationException e) {
							LOG.error("Failed to remove line tracking icon", e);
						}
					}
				}
				this.disabled = disabled;
			}
		}

		private void safeRemoveTrackingIcon(GutterIconInfo iconInfo) {
			if (gutter != null && iconInfo != null) {
				gutter.removeTrackingIcon(iconInfo);
			}
		}

	}

	@Override
	protected RTextAreaUI createRTextAreaUI() {
		// IconRowHeader won't fire an event when people click on it for adding/removing icons,
		// so our poor breakpoints won't be set if we don't hijack IconRowHeader.
		return new RSyntaxTextAreaUI(this) {
			@Override
			public EditorKit getEditorKit(JTextComponent tc) {
				return new RSyntaxTextAreaEditorKit() {
					private static final long serialVersionUID = -1111111202103170740L;

					@Override
					public IconRowHeader createIconRowHeader(RTextArea textArea) {
						return new FoldingAwareIconRowHeader((RSyntaxTextArea) textArea) {
							private static final long serialVersionUID = -1111111202103170739L;

							@Override
							public void mousePressed(MouseEvent e) {
								int offs = textArea.viewToModel2D(e.getPoint());
								if (offs > -1) {
									model.setBreakpoint(offs);
								}
							}
						};
					}
				};
			}
		};
	}

	@Override
	public CodeAreaSyncer createCodeAreaSyncer() {
		return new SmaliSyncer(this);
	}

	@Override
	public boolean sync(CodeAreaSyncer codeAreaSyncer) {
		return codeAreaSyncer.syncTo(this);
	}
}
