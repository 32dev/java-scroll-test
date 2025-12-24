package ai20;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Stack;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.*;

public class MidiRhythmEditor extends JFrame {
	private JTable table;
	private DefaultTableModel tableModel;
	private JScrollPane scrollPane;

	private final int TICKS_PER_ROW = 10;
	private final int COLUMN_COUNT = 8;
	private final int JUDGMENT_LINE_OFFSET = 30;
	private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	private Sequencer sequencer;
	private Timer uiSyncTimer;
	private float speedMultiplier = 1.0f;

	// Undo/Redo 스택
	private Stack<Object[][]> undoStack = new Stack<>();
	private Stack<Object[][]> redoStack = new Stack<>();

	// 클립보드 변수
	private NoteData clipboardNode = null;

	public MidiRhythmEditor() {
		setTitle("Rhythm Editor Pro - Undo/Redo & Clipboard & Piano Chord");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1100, 1000);

		initMidiEngine();
		initializeComponents();
		initializeMenu();
		loadMidiFile("input.mid");

		setLocationRelativeTo(null);
		setVisible(true);
		SwingUtilities.invokeLater(() -> scrollToTick(0));
	}

	private void initMidiEngine() {
		try {
			sequencer = MidiSystem.getSequencer();
			sequencer.open();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// --- 실행 취소를 위한 상태 저장 ---
	private void saveState() {
		int rows = tableModel.getRowCount();
		int cols = tableModel.getColumnCount();
		Object[][] state = new Object[rows][cols];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				state[r][c] = tableModel.getValueAt(r, c);
			}
		}
		undoStack.push(state);
		redoStack.clear(); // 새로운 작업 시 Redo 초기화
	}

	private void undo() {
		if (undoStack.isEmpty())
			return;
		redoStack.push(getCurrentState());
		restoreState(undoStack.pop());
	}

	private void redo() {
		if (redoStack.isEmpty())
			return;
		undoStack.push(getCurrentState());
		restoreState(redoStack.pop());
	}

	private Object[][] getCurrentState() {
		int rows = tableModel.getRowCount();
		Object[][] state = new Object[rows][COLUMN_COUNT];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < COLUMN_COUNT; c++) {
				state[r][c] = tableModel.getValueAt(r, c);
			}
		}
		return state;
	}

	private void restoreState(Object[][] state) {
		for (int r = 0; r < state.length; r++) {
			for (int c = 0; c < COLUMN_COUNT; c++) {
				tableModel.setValueAt(state[r][c], r, c);
			}
		}
	}

	private String getNoteName(int pitch) {
		return NOTE_NAMES[pitch % 12] + (pitch / 12 - 1);
	}

	private void initializeComponents() {
		tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}

			@Override
			public void setValueAt(Object aValue, int row, int column) {
				super.setValueAt(aValue, row, column);
			}
		};

		table = new JTable(tableModel);
		table.setRowHeight(24);
		table.setBackground(Color.BLACK);
		table.setGridColor(new Color(45, 45, 45));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setCellSelectionEnabled(true);

		// DnD 설정
		table.setDragEnabled(true);
		table.setDropMode(DropMode.USE_SELECTION);
		table.setTransferHandler(new NoteTransferHandler());

		setupColumns();

		scrollPane = new JScrollPane(table);
		scrollPane.getVerticalScrollBar().setUnitIncrement(20);
		add(scrollPane, BorderLayout.CENTER);
		setupRowHeader();
	}

	private void setupColumns() {
		String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
		for (int i = 0; i < COLUMN_COUNT; i++) {
			TableColumn col = table.getColumnModel().getColumn(i);
			col.setHeaderValue(headers[i]);
			col.setPreferredWidth(i == 4 ? 120 : 90);
			col.setCellRenderer(new NoteCellRenderer());
		}
	}

	private void initializeMenu() {
		JMenuBar menuBar = new JMenuBar();
		JButton btnSave = new JButton("저장");
		JLabel lblInfo = new JLabel(" [Ctrl+Z/Y]실행취소 | [Ctrl+C/V]복사붙여넣기 | [Del]삭제 ");

		btnSave.addActionListener(e -> saveTableToTxt());
		menuBar.add(btnSave);
		menuBar.add(Box.createHorizontalGlue());
		menuBar.add(lblInfo);
		setJMenuBar(menuBar);

		InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap am = getRootPane().getActionMap();

		// 재생/정지
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "play");
		am.put("play", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				togglePlayback();
			}
		});

		// Undo (Ctrl+Z)
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
		am.put("undo", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				undo();
			}
		});

		// Redo (Ctrl+Y or Ctrl+Shift+Z)
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo");
		am.put("redo", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				redo();
			}
		});

		// Copy (Ctrl+C)
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
		am.put("copy", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int r = table.getSelectedRow(), c = table.getSelectedColumn();
				if (r != -1 && table.getValueAt(r, c) instanceof NoteData nd)
					clipboardNode = new NoteData(nd.pitch);
			}
		});
		// --- 저장 (Ctrl + S) 단축키 추가 ---
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
		am.put("save", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				saveTableToTxt(); // 기존 저장 로직 호출
			}
		});
		// Paste (Ctrl+V)
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste");
		am.put("paste", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int r = table.getSelectedRow(), c = table.getSelectedColumn();
				if (r != -1 && clipboardNode != null) {
					saveState();
					tableModel.setValueAt(new NoteData(clipboardNode.pitch), r, c);
				}
			}
		});

		// Delete (Del)
		im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
		am.put("delete", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int r = table.getSelectedRow(), c = table.getSelectedColumn();
				if (r != -1) {
					saveState();
					tableModel.setValueAt(null, r, c);
				}
			}
		});
	}

	// --- 커스텀 렌더러 (피아노 코드 표시) ---
	private class NoteCellRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
				int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (value instanceof NoteData data) {
				c.setBackground(getOctaveColor(column, (data.pitch / 12) - 1));
				c.setForeground(Color.BLACK);
				setText(data.pitch + " (" + getNoteName(data.pitch) + ")");
				setHorizontalAlignment(SwingConstants.CENTER);
			} else {
				c.setBackground(
						row == table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET ? new Color(70, 20, 20) : Color.BLACK);
				setText("");
			}
			if (isSelected)
				c.setBackground(c.getBackground().brighter());
			return c;
		}
	}

	// --- DnD 핸들러 (Undo 지원) ---
	private class NoteTransferHandler extends TransferHandler {
		@Override
		public int getSourceActions(JComponent c) {
			return MOVE;
		}

		@Override
		protected Transferable createTransferable(JComponent c) {
			int r = table.getSelectedRow(), col = table.getSelectedColumn();
			NoteData nd = (NoteData) table.getValueAt(r, col);
			return nd != null ? new NoteTransferable(nd, r, col) : null;
		}

		@Override
		public boolean canImport(TransferSupport support) {
			return support.isDataFlavorSupported(NoteTransferable.FLAVOR);
		}

		@Override
		public boolean importData(TransferSupport support) {
			if (!canImport(support))
				return false;
			JTable.DropLocation dl = (JTable.DropLocation) support.getDropLocation();
			try {
				NoteTransferable t = (NoteTransferable) support.getTransferable()
						.getTransferData(NoteTransferable.FLAVOR);
				saveState(); // 드롭 직전 상태 저장
				NoteData targetData = (NoteData) table.getValueAt(dl.getRow(), dl.getColumn());
				tableModel.setValueAt(targetData, t.sourceRow, t.sourceCol);
				tableModel.setValueAt(t.data, dl.getRow(), dl.getColumn());
				return true;
			} catch (Exception e) {
				return false;
			}
		}
	}

	private static class NoteTransferable implements Transferable {
		public static final DataFlavor FLAVOR = new DataFlavor(NoteData.class, "NoteData");
		NoteData data;
		int sourceRow, sourceCol;

		NoteTransferable(NoteData d, int r, int c) {
			data = d;
			sourceRow = r;
			sourceCol = c;
		}

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { FLAVOR };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor f) {
			return FLAVOR.equals(f);
		}

		@Override
		public Object getTransferData(DataFlavor f) {
			return this;
		}
	}

	// --- 유틸리티 메서드들 ---
	private Color getOctaveColor(int col, int octave) {
		if (col == 0)
			return new Color(255, 180, 180);
		if (col == 4)
			return new Color(255, 255, 180);
		return new Color(180, 220, 255);
	}

	private void togglePlayback() {
		if (sequencer.isRunning()) {
			sequencer.stop();
			if (uiSyncTimer != null)
				uiSyncTimer.stop();
		} else {
			sequencer.setTickPosition(calculateTickFromView());
			sequencer.start();
			uiSyncTimer = new Timer(10, e -> syncTableSmooth());
			uiSyncTimer.start();
		}
	}

	private void syncTableSmooth() {
		if (!sequencer.isRunning())
			return;
		long currentTick = sequencer.getTickPosition();
		int rowHeight = table.getRowHeight();
		float currentNoteY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * rowHeight)
				- (currentTick * ((float) rowHeight / TICKS_PER_ROW));
		int targetViewY = (int) (currentNoteY
				- (scrollPane.getViewport().getHeight() - (JUDGMENT_LINE_OFFSET * rowHeight)));
		scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetViewY)));
	}

	private long calculateTickFromView() {
		int viewBottomY = scrollPane.getViewport().getViewPosition().y + scrollPane.getViewport().getHeight();
		int judgmentLineY = viewBottomY - (JUDGMENT_LINE_OFFSET * table.getRowHeight());
		float diffY = ((table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET) * table.getRowHeight()) - judgmentLineY;
		return (long) Math.max(0, diffY / ((float) table.getRowHeight() / TICKS_PER_ROW));
	}

	private void scrollToTick(long tick) {
		int targetRow = (table.getRowCount() - 1) - JUDGMENT_LINE_OFFSET - (int) (tick / TICKS_PER_ROW);
		Rectangle rect = table.getCellRect(targetRow, 0, true);
		int targetY = rect.y - scrollPane.getViewport().getHeight() + (JUDGMENT_LINE_OFFSET * table.getRowHeight());
		scrollPane.getViewport().setViewPosition(new Point(0, Math.max(0, targetY)));
	}

	public void loadMidiFile(String filePath) {
		try {
			File file = new File(filePath);
			if (!file.exists()) {
				tableModel.setRowCount(3000);
				return;
			}
			Sequence seq = MidiSystem.getSequence(file);
			sequencer.setSequence(seq);
			int totalRows = (int) (seq.getTickLength() / TICKS_PER_ROW) + 500;
			tableModel.setRowCount(totalRows);
			for (Track track : seq.getTracks()) {
				for (int i = 0; i < track.size(); i++) {
					MidiEvent event = track.get(i);
					MidiMessage msg = event.getMessage();
					if (msg instanceof ShortMessage sm && sm.getCommand() == ShortMessage.NOTE_ON
							&& sm.getData2() > 0) {
						int row = (totalRows - 1) - JUDGMENT_LINE_OFFSET - (int) (event.getTick() / TICKS_PER_ROW);
						int col = (sm.getData1() % 12) % COLUMN_COUNT;
						if (row >= 0)
							tableModel.setValueAt(new NoteData(sm.getData1()), row, col);
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void saveTableToTxt() {
		try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
			for (int r = tableModel.getRowCount() - 1; r >= 0; r--) {
				for (int c = 0; c < COLUMN_COUNT; c++) {
					if (tableModel.getValueAt(r, c) instanceof NoteData nd) {
						int timeT = (tableModel.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - r) * 10;
						out.println("{\"pitch\":" + nd.pitch + ", \"name\":\"" + getNoteName(nd.pitch) + "\", \"t\":"
								+ timeT + "},");
					}
				}
			}
			JOptionPane.showMessageDialog(this, "저장 성공!");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void setupRowHeader() {
		JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
			public int getSize() {
				return table.getRowCount();
			}

			public String getElementAt(int index) {
				int val = table.getRowCount() - 1 - JUDGMENT_LINE_OFFSET - index;
				return val >= 0 ? String.valueOf(val) : "-";
			}
		});
		rowHeader.setFixedCellHeight(table.getRowHeight());
		rowHeader.setFixedCellWidth(50);
		rowHeader.setBackground(new Color(20, 20, 20));
		rowHeader.setForeground(Color.GRAY);
		scrollPane.setRowHeaderView(rowHeader);
	}

	static class NoteData {
		int pitch;

		NoteData(int pitch) {
			this.pitch = pitch;
		}
	}

	public static void main(String[] args) {
		System.setProperty("sun.java2d.uiScale", "1.0");
		SwingUtilities.invokeLater(MidiRhythmEditor::new);
	}
}