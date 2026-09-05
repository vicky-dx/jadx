package jadx.gui.patching.history;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.dialog.CommonDialog;
import jadx.gui.utils.UiUtils;

public class PatchDiffDialog extends CommonDialog {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(PatchDiffDialog.class);

	private final String classType;
	private final String leftContent;
	private final String rightContent;
	private final String leftTitle;
	private final String rightTitle;
	private final Runnable onRevert;

	private RSyntaxTextArea leftArea;
	private RSyntaxTextArea rightArea;
	private RTextScrollPane leftScroll;
	private RTextScrollPane rightScroll;

	private final List<Edit> activeEdits = new ArrayList<>();
	private int currentDiffIndex = -1;
	private JLabel diffStatusLabel;
	private JButton prevDiffBtn;
	private JButton nextDiffBtn;
	private boolean isSyncingScroll = false;

	public PatchDiffDialog(MainWindow mainWindow, String classType, String title,
			String leftContent, String leftTitle,
			String rightContent, String rightTitle,
			Runnable onRevert) {
		super(mainWindow);
		this.classType = classType;
		this.leftContent = leftContent != null ? leftContent : "";
		this.rightContent = rightContent != null ? rightContent : "";
		this.leftTitle = leftTitle != null ? leftTitle : "Historical Version";
		this.rightTitle = rightTitle != null ? rightTitle : "Current Active Version";
		this.onRevert = onRevert;

		setTitle(title != null ? title : "Patch Diff — " + classType);
		initUI();
		computeAndApplyDiff();
	}

	private void initUI() {
		JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		// Top toolbar with class info, diff navigation, and legend
		JPanel topBar = new JPanel(new BorderLayout(8, 4));
		topBar.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
				BorderFactory.createEmptyBorder(4, 4, 8, 4)));

		JPanel leftInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
		JLabel headerLabel = new JLabel("Class: " + classType);
		headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
		leftInfoPanel.add(headerLabel);

		diffStatusLabel = new JLabel("Computing differences...");
		diffStatusLabel.setFont(diffStatusLabel.getFont().deriveFont(Font.PLAIN, 12f));
		leftInfoPanel.add(diffStatusLabel);
		topBar.add(leftInfoPanel, BorderLayout.WEST);

		// Nav buttons + Legend on the right
		JPanel rightNavPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
		prevDiffBtn = new JButton("▲ Prev Change");
		prevDiffBtn.setEnabled(false);
		prevDiffBtn.addActionListener(e -> navigateDiff(-1));

		nextDiffBtn = new JButton("▼ Next Change");
		nextDiffBtn.setEnabled(false);
		nextDiffBtn.addActionListener(e -> navigateDiff(1));

		JLabel legendRemoved = new JLabel(" ■ Removed ");
		legendRemoved.setForeground(new Color(180, 40, 40));
		JLabel legendAdded = new JLabel(" ■ Added ");
		legendAdded.setForeground(new Color(30, 140, 40));

		rightNavPanel.add(legendRemoved);
		rightNavPanel.add(legendAdded);
		rightNavPanel.add(Box.createHorizontalStrut(8));
		rightNavPanel.add(prevDiffBtn);
		rightNavPanel.add(nextDiffBtn);
		topBar.add(rightNavPanel, BorderLayout.EAST);

		mainPanel.add(topBar, BorderLayout.NORTH);

		// Left Pane (Historical / Baseline)
		JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
		JLabel leftLbl = new JLabel(leftTitle);
		leftLbl.setFont(leftLbl.getFont().deriveFont(Font.BOLD, 12f));
		leftLbl.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		leftArea = createCodeArea(leftContent);
		leftScroll = new RTextScrollPane(leftArea);
		leftPanel.add(leftLbl, BorderLayout.NORTH);
		leftPanel.add(leftScroll, BorderLayout.CENTER);

		// Right Pane (Current Active)
		JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
		JLabel rightLbl = new JLabel(rightTitle);
		rightLbl.setFont(rightLbl.getFont().deriveFont(Font.BOLD, 12f));
		rightLbl.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
		rightArea = createCodeArea(rightContent);
		rightScroll = new RTextScrollPane(rightArea);
		rightPanel.add(rightLbl, BorderLayout.NORTH);
		rightPanel.add(rightScroll, BorderLayout.CENTER);

		// Synchronize scroll bars between left and right
		setupScrollSync(leftScroll, rightScroll);

		// Split Pane
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
		splitPane.setResizeWeight(0.5);
		splitPane.setDividerLocation(520);
		mainPanel.add(splitPane, BorderLayout.CENTER);

		// Bottom Buttons
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
		buttonPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		buttonPanel.add(Box.createHorizontalGlue());

		if (onRevert != null) {
			JButton revertBtn = new JButton("⏪ Rollback to this Version");
			revertBtn.addActionListener(e -> {
				dispose();
				onRevert.run();
			});
			buttonPanel.add(revertBtn);
			buttonPanel.add(Box.createRigidArea(new Dimension(8, 0)));
		}

		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(e -> dispose());
		buttonPanel.add(closeBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		getContentPane().add(mainPanel);
		pack();
		setSize(1050, 680);
		setLocationRelativeTo(mainWindow);
	}

	private RSyntaxTextArea createCodeArea(String text) {
		RSyntaxTextArea area = new RSyntaxTextArea(text);
		area.setEditable(false);
		area.setSyntaxEditingStyle(AbstractCodeArea.SYNTAX_STYLE_SMALI);
		area.setCodeFoldingEnabled(true);
		area.setAntiAliasingEnabled(true);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		if (mainWindow != null && mainWindow.getEditorThemeManager() != null) {
			mainWindow.getEditorThemeManager().apply(area);
		}
		// Turn off the default active caret line highlight so diff colors remain prominent
		area.setHighlightCurrentLine(false);
		return area;
	}

	private void setupScrollSync(RTextScrollPane left, RTextScrollPane right) {
		JScrollBar leftV = left.getVerticalScrollBar();
		JScrollBar rightV = right.getVerticalScrollBar();
		leftV.addAdjustmentListener(e -> {
			if (!isSyncingScroll) {
				isSyncingScroll = true;
				rightV.setValue(e.getValue());
				isSyncingScroll = false;
			}
		});
		rightV.addAdjustmentListener(e -> {
			if (!isSyncingScroll) {
				isSyncingScroll = true;
				leftV.setValue(e.getValue());
				isSyncingScroll = false;
			}
		});

		JScrollBar leftH = left.getHorizontalScrollBar();
		JScrollBar rightH = right.getHorizontalScrollBar();
		leftH.addAdjustmentListener(e -> {
			if (!isSyncingScroll) {
				isSyncingScroll = true;
				rightH.setValue(e.getValue());
				isSyncingScroll = false;
			}
		});
		rightH.addAdjustmentListener(e -> {
			if (!isSyncingScroll) {
				isSyncingScroll = true;
				leftH.setValue(e.getValue());
				isSyncingScroll = false;
			}
		});
	}

	private void computeAndApplyDiff() {
		try {
			RawText rawA = new RawText(leftContent.getBytes(StandardCharsets.UTF_8));
			RawText rawB = new RawText(rightContent.getBytes(StandardCharsets.UTF_8));
			DiffAlgorithm diffAlgorithm = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM);
			EditList edits = diffAlgorithm.diff(RawTextComparator.DEFAULT, rawA, rawB);

			activeEdits.clear();
			for (Edit edit : edits) {
				if (edit.getType() != Edit.Type.EMPTY) {
					activeEdits.add(edit);
				}
			}

			boolean isDark = UiUtils.isDarkTheme(leftArea.getBackground());
			Color delColor = isDark ? new Color(90, 25, 25, 180) : new Color(255, 210, 210, 220);
			Color addColor = isDark ? new Color(25, 80, 35, 180) : new Color(210, 255, 210, 220);
			Color modOldColor = isDark ? new Color(90, 50, 20, 180) : new Color(255, 225, 190, 220);
			Color modNewColor = isDark ? new Color(25, 70, 90, 180) : new Color(205, 235, 255, 220);

			for (Edit edit : activeEdits) {
				switch (edit.getType()) {
					case DELETE:
						highlightRange(leftArea, edit.getBeginA(), edit.getEndA(), delColor);
						break;
					case INSERT:
						highlightRange(rightArea, edit.getBeginB(), edit.getEndB(), addColor);
						break;
					case REPLACE:
						highlightRange(leftArea, edit.getBeginA(), edit.getEndA(), modOldColor);
						highlightRange(rightArea, edit.getBeginB(), edit.getEndB(), modNewColor);
						break;
					default:
						break;
				}
			}

			int count = activeEdits.size();
			if (count == 0) {
				diffStatusLabel.setText(" |  ✔ No differences found (Versions are identical)");
				diffStatusLabel.setForeground(isDark ? new Color(100, 200, 100) : new Color(0, 140, 0));
				prevDiffBtn.setEnabled(false);
				nextDiffBtn.setEnabled(false);
			} else {
				diffStatusLabel.setText(String.format(" |  Found %d change%s", count, count > 1 ? "s" : ""));
				prevDiffBtn.setEnabled(true);
				nextDiffBtn.setEnabled(true);
				// Automatically jump to the first difference when dialog renders
				SwingUtilities.invokeLater(() -> jumpToDiff(0));
			}
		} catch (Exception e) {
			LOG.error("Failed to compute smali diff", e);
			diffStatusLabel.setText(" |  Error calculating diff");
		}
	}

	private void highlightRange(RSyntaxTextArea area, int startLine, int endLine, Color color) {
		int lineCount = area.getLineCount();
		for (int line = startLine; line < endLine; line++) {
			if (line >= 0 && line < lineCount) {
				try {
					area.addLineHighlight(line, color);
				} catch (BadLocationException e) {
					LOG.debug("Highlight error at line {}: {}", line, e.getMessage());
				}
			}
		}
	}

	private void navigateDiff(int direction) {
		if (activeEdits.isEmpty()) {
			return;
		}
		int newIndex;
		if (currentDiffIndex == -1) {
			newIndex = direction > 0 ? 0 : activeEdits.size() - 1;
		} else {
			newIndex = (currentDiffIndex + direction + activeEdits.size()) % activeEdits.size();
		}
		jumpToDiff(newIndex);
	}

	private void jumpToDiff(int index) {
		if (index < 0 || index >= activeEdits.size()) {
			return;
		}
		currentDiffIndex = index;
		Edit edit = activeEdits.get(index);
		diffStatusLabel.setText(String.format(" |  Change %d of %d (%s)",
				index + 1, activeEdits.size(), edit.getType().name()));

		int leftLine = edit.getBeginA();
		int rightLine = edit.getBeginB();

		scrollToLine(leftArea, leftLine);
		scrollToLine(rightArea, rightLine);
	}

	@SuppressWarnings("deprecation")
	private void scrollToLine(RSyntaxTextArea area, int line) {
		try {
			if (line < 0 || line >= area.getLineCount()) {
				return;
			}
			int offset = area.getLineStartOffset(line);
			area.setCaretPosition(offset);

			JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, area);
			if (viewport != null) {
				Rectangle r = area.modelToView(offset);
				if (r != null) {
					int extentHeight = viewport.getExtentSize().height;
					Dimension viewSize = viewport.getViewSize();
					int y = Math.max(0, r.y - extentHeight / 3);
					if (viewSize != null) {
						y = Math.min(y, viewSize.height - extentHeight);
					}
					viewport.setViewPosition(new Point(0, Math.max(0, y)));
				}
			}
		} catch (Exception e) {
			LOG.debug("Error scrolling to line {}: {}", line, e.getMessage());
		}
	}
}
