package jadx.gui.patching.history;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.CommonDialog;

public class PatchDiffDialog extends CommonDialog {
	private static final long serialVersionUID = 1L;

	private final String classType;
	private final String leftContent;
	private final String rightContent;
	private final String leftTitle;
	private final String rightTitle;
	private final Runnable onRevert;

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
	}

	private void initUI() {
		JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

		// Top info
		JLabel headerLabel = new JLabel("Class: " + classType);
		headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
		mainPanel.add(headerLabel, BorderLayout.NORTH);

		// Left Pane (Historical / Baseline)
		JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
		JLabel leftLbl = new JLabel(leftTitle);
		leftLbl.setFont(leftLbl.getFont().deriveFont(Font.BOLD));
		RSyntaxTextArea leftArea = createCodeArea(leftContent);
		RTextScrollPane leftScroll = new RTextScrollPane(leftArea);
		leftPanel.add(leftLbl, BorderLayout.NORTH);
		leftPanel.add(leftScroll, BorderLayout.CENTER);

		// Right Pane (Current Active)
		JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
		JLabel rightLbl = new JLabel(rightTitle);
		rightLbl.setFont(rightLbl.getFont().deriveFont(Font.BOLD));
		RSyntaxTextArea rightArea = createCodeArea(rightContent);
		RTextScrollPane rightScroll = new RTextScrollPane(rightArea);
		rightPanel.add(rightLbl, BorderLayout.NORTH);
		rightPanel.add(rightScroll, BorderLayout.CENTER);

		// Split Pane
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
		splitPane.setResizeWeight(0.5);
		splitPane.setDividerLocation(450);
		mainPanel.add(splitPane, BorderLayout.CENTER);

		// Bottom Buttons
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
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
		setSize(950, 600);
		setLocationRelativeTo(mainWindow);
	}

	private RSyntaxTextArea createCodeArea(String text) {
		RSyntaxTextArea area = new RSyntaxTextArea(text);
		area.setEditable(false);
		area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
		area.setCodeFoldingEnabled(true);
		area.setAntiAliasingEnabled(true);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		return area;
	}
}
