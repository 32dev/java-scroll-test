package ai03;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.File;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
	private JTable table;
	private DefaultTableModel tableModel;
	private JScrollPane scrollPane;

	// 설정값
	private final int TICKS_PER_ROW = 20; // 1행당 MIDI Tick 수 (해상도)
	private final int COLUMN_COUNT = 8;

	private double currentBPM = 120.0; // 기본 BPM (파일에서 읽어올 예정)
	private int resolution = 480; // PPQ (파일에서 읽어올 예정)

	public MidiRhythmEditor() {
		setTitle("Rhythm Game MIDI Editor (BPM Sync)");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(800, 1000);

		initializeComponents();

		// 샘플 MIDI 로드
		loadMidiFile("input.mid");

		setLocationRelativeTo(null);
		setVisible(true);
		table.changeSelection(table.getRowCount(), 0, false, false);
	}

	private void initializeComponents() {
		tableModel = new DefaultTableModel(500, COLUMN_COUNT) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		table = new JTable(tableModel);
		table.setRowHeight(25);
		table.setBackground(Color.BLACK);
		table.setGridColor(Color.DARK_GRAY);
		table.setSelectionBackground(new Color(100, 100, 100, 100));
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		setupColumns();

		scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(Color.BLACK);
		add(scrollPane, BorderLayout.CENTER);

		setupRowHeader();
	}

	private void setupColumns() {
		String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
		TableColumnModel cm = table.getColumnModel();

		for (int i = 0; i < cm.getColumnCount(); i++) {
			cm.getColumn(i).setPreferredWidth(i == 4 ? 80 : 55);
			if (i < headers.length)
				cm.getColumn(i).setHeaderValue(headers[i]);

			cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
				@Override
				public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
						boolean hasFocus, int row, int column) {
					Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
					if (value != null && !value.toString().isEmpty()) {
						c.setBackground(getNoteColor(column));
						c.setForeground(Color.BLACK);
					} else {
						c.setBackground(Color.BLACK);
						c.setForeground(Color.BLACK);
					}
					((JComponent) c).setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
					return c;
				}
			});
		}
	}

	private Color getNoteColor(int col) {
		switch (col) {
		case 0:
			return Color.PINK;
		case 4:
			return Color.YELLOW;
		default:
			return Color.CYAN;
		}
	}

	private void setupRowHeader() {
		JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
			public int getSize() {
				return table.getRowCount();
			}

			public String getElementAt(int index) {
				return String.valueOf(table.getRowCount() - index);
			}
		});
		rowHeader.setFixedCellHeight(table.getRowHeight());
		rowHeader.setFixedCellWidth(50);
		rowHeader.setBackground(new Color(30, 30, 30));
		rowHeader.setForeground(Color.LIGHT_GRAY);
		scrollPane.setRowHeaderView(rowHeader);
	}

	public void loadMidiFile(String filePath) {
		try {
			Sequence sequence = MidiSystem.getSequence(new File(filePath));
			this.resolution = sequence.getResolution(); // MIDI 파일의 PPQ 가져오기

			int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 100;
			tableModel.setRowCount(totalRows);
			for (Track track : sequence.getTracks()) {
				for (int i = 0; i < track.size(); i++) {
					MidiEvent event = track.get(i);

					MidiMessage message = event.getMessage();

					// MetaMessage에서 BPM 정보 추출
					if (message instanceof MetaMessage) {
						MetaMessage mm = (MetaMessage) message;
						if (mm.getType() == 0x51) { // Tempo Meta Event
							byte[] data = mm.getData();
							int tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
							this.currentBPM = 60000000.0 / tempo;
							System.out.println("Detected BPM: " + currentBPM);
						}
					}

					if (message instanceof ShortMessage) {
						ShortMessage sm = (ShortMessage) message;

						if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
							int pitch = sm.getData1();
							long tick = event.getTick();
							int row = (int) (tick / TICKS_PER_ROW);
							System.out.println("{\"seq\":" + pitch + ", \"t\":" + (row) * 10 + " }, ");
							int col = pitch % 8;
							int actualRow = tableModel.getRowCount() - (row - 1);
							if (actualRow >= 0)
								tableModel.setValueAt(tick, actualRow, col);
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		System.setProperty("sun.java2d.uiScale", "1.0");
		SwingUtilities.invokeLater(MidiRhythmEditor::new);
	}
}