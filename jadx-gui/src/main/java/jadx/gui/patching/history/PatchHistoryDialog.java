package jadx.gui.patching.history;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import jadx.gui.patching.ModifiedDexManager;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.dialog.CommonDialog;

public class PatchHistoryDialog extends CommonDialog {
	private static final long serialVersionUID = 1L;

	private final String classType;
	private final String currentSmali;
	private final Consumer<String> onApplyCode;
	private final List<PatchCommit> history;

	private JTable table;
	private DefaultTableModel tableModel;

	public PatchHistoryDialog(MainWindow mainWindow, String classType, String currentSmali, Consumer<String> onApplyCode) {
		super(mainWindow);
		this.classType = classType;
		this.currentSmali = currentSmali;
		this.onApplyCode = onApplyCode;
		this.history = PatchHistoryManager.getInstance().getClassHistory(classType);

		setTitle("Patch Timeline & History — " + classType);
		initUI();
	}

	private void initUI() {
		JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

		JLabel headerLbl = new JLabel("Class History: " + classType + " (" + history.size() + " checkpoints)");
		headerLbl.setFont(headerLbl.getFont().deriveFont(Font.BOLD, 13f));
		mainPanel.add(headerLbl, BorderLayout.NORTH);

		String[] colNames = {"ID", "Time", "Checkpoint / Message", "Type"};
		tableModel = new DefaultTableModel(colNames, 0) {
			@Override
			public boolean isCellEditable(int r, int c) {
				return false;
			}
		};

		for (int i = 0; i < history.size(); i++) {
			PatchCommit commit = history.get(i);
			String type = "Edit";
			if (commit.isBaseline()) {
				type = "Original Baseline";
			} else if (commit.isDeployMilestone()) {
				type = "Deploy Milestone";
			} else if (commit.isExportMilestone()) {
				type = "Export Milestone";
			} else if (commit.isReloadSnapshot()) {
				type = "Reload Snapshot";
			}
			if (i == 0) {
				type += " (Current Active)";
			}
			tableModel.addRow(new Object[]{
					commit.getShortHash(),
					commit.getFormattedTime(),
					commit.getMessage(),
					type
			});
		}

		table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setRowHeight(22);
		if (table.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		}
		table.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				if (e.getClickCount() == 2) {
					showDiff();
				}
			}
		});

		JScrollPane scrollPane = new JScrollPane(table);
		scrollPane.setPreferredSize(new Dimension(650, 240));
		mainPanel.add(scrollPane, BorderLayout.CENTER);

		// Buttons
		JPanel btnPanel = new JPanel();
		btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.LINE_AXIS));

		JButton diffBtn = new JButton("🔍 View Changes in Checkpoint");
		JButton diffCurrentBtn = new JButton("🔍 Compare vs Current");
		JButton rollbackBtn = new JButton("⏪ Rollback to Selected");
		JButton closeBtn = new JButton("Close");

		diffBtn.addActionListener(e -> showDiff());
		diffCurrentBtn.addActionListener(e -> showDiffWithCurrent());
		rollbackBtn.addActionListener(e -> rollbackSelected());
		closeBtn.addActionListener(e -> dispose());

		btnPanel.add(diffBtn);
		btnPanel.add(Box.createRigidArea(new Dimension(6, 0)));
		btnPanel.add(diffCurrentBtn);
		btnPanel.add(Box.createRigidArea(new Dimension(8, 0)));
		btnPanel.add(rollbackBtn);
		btnPanel.add(Box.createHorizontalGlue());
		btnPanel.add(closeBtn);

		mainPanel.add(btnPanel, BorderLayout.SOUTH);

		getContentPane().add(mainPanel);
		pack();
		setSize(800, 420);
		setLocationRelativeTo(mainWindow);
	}

	private void showDiff() {
		int row = table.getSelectedRow();
		if (row < 0 || row >= history.size()) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint from the list.", "History", JOptionPane.WARNING_MESSAGE);
			return;
		}
		PatchCommit selected = history.get(row);
		String selectedSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(classType, selected.getFullHash());
		if (selectedSmali == null) {
			JOptionPane.showMessageDialog(this, "Could not load smali for selected checkpoint.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		String leftCode;
		String leftTitle;
		String rightCode;
		String rightTitle;
		String dialogTitle;

		if (row < history.size() - 1) {
			// Compare against previous checkpoint to show what this checkpoint changed
			PatchCommit parent = history.get(row + 1);
			String parentSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(classType, parent.getFullHash());
			if (parentSmali != null) {
				leftCode = parentSmali;
				leftTitle = "Before: " + parent.getShortHash() + " (" + parent.getFormattedTime() + ")";
				rightCode = selectedSmali;
				rightTitle = "After: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")";
				dialogTitle = "Diff: Changes in Checkpoint " + selected.getShortHash() + " (vs " + parent.getShortHash() + ")";
			} else {
				leftCode = selectedSmali;
				leftTitle = "Checkpoint: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")";
				rightCode = currentSmali;
				rightTitle = "Current Active Version";
				dialogTitle = "Diff: " + selected.getShortHash() + " vs Current";
			}
		} else {
			// Baseline has no parent, compare against Current Active
			leftCode = selectedSmali;
			leftTitle = "Original Baseline: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")";
			rightCode = currentSmali;
			rightTitle = "Current Active Version";
			dialogTitle = "Diff: Original Baseline vs Current Active";
		}

		PatchDiffDialog diffDialog = new PatchDiffDialog(
				mainWindow, classType,
				dialogTitle,
				leftCode, leftTitle,
				rightCode, rightTitle,
				() -> {
					if (onApplyCode != null) {
						onApplyCode.accept(selectedSmali);
					}
					dispose();
				}
		);
		diffDialog.setVisible(true);
	}

	private void showDiffWithCurrent() {
		int row = table.getSelectedRow();
		if (row < 0 || row >= history.size()) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint from the list.", "History", JOptionPane.WARNING_MESSAGE);
			return;
		}
		PatchCommit selected = history.get(row);
		String selectedSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(classType, selected.getFullHash());
		if (selectedSmali == null) {
			JOptionPane.showMessageDialog(this, "Could not load smali for selected checkpoint.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		PatchDiffDialog diffDialog = new PatchDiffDialog(
				mainWindow, classType,
				"Diff: Checkpoint " + selected.getShortHash() + " vs Current Active",
				selectedSmali, "Checkpoint: " + selected.getShortHash() + " (" + selected.getFormattedTime() + ")",
				currentSmali, "Current Active Version",
				() -> {
					if (onApplyCode != null) {
						onApplyCode.accept(selectedSmali);
					}
					dispose();
				}
		);
		diffDialog.setVisible(true);
	}

	private void rollbackSelected() {
		int row = table.getSelectedRow();
		if (row < 0 || row >= history.size()) {
			JOptionPane.showMessageDialog(this, "Please select a checkpoint to rollback to.", "History", JOptionPane.WARNING_MESSAGE);
			return;
		}
		PatchCommit selected = history.get(row);
		int confirm = JOptionPane.showConfirmDialog(this,
				"Are you sure you want to rollback to checkpoint:\n[" + selected.getShortHash() + "] " + selected.getMessage() + "?",
				"Confirm Rollback",
				JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}

		String historicalSmali = PatchHistoryManager.getInstance().getSmaliAtCommit(classType, selected.getFullHash());
		if (historicalSmali != null) {
			String normCurrent = (currentSmali != null) ? currentSmali.replace("\r\n", "\n").replace('\r', '\n').trim() : "";
			String normHistorical = historicalSmali.replace("\r\n", "\n").replace('\r', '\n').trim();
			if (normHistorical.equals(normCurrent)) {
				JOptionPane.showMessageDialog(this,
						"Current active code is already identical to checkpoint [" + selected.getShortHash() + "].\nNo rollback needed.",
						"Rollback", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			if (selected.isBaseline()) {
				ModifiedDexManager.getInstance().unregisterModifiedClass(classType);
			}
			if (onApplyCode != null) {
				dispose();
				onApplyCode.accept(historicalSmali);
			}
		}
	}
}
