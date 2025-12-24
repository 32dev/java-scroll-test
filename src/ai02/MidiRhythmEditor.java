package ai02;

import java.awt.*;
import java.io.File;
import java.util.Vector;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
	private JTable table;
	private DefaultTableModel tableModel;
	private JScrollPane scrollPane;

	// 설정값
	private final int TICKS_PER_ROW = 10; // 1행당 할당될 MIDI Tick 수 (해상도 조절)
	private final int COLUMN_COUNT = 8; // 주요 게임 키 개수 (Scr, S, D, F, Space, J, K, L)

	public MidiRhythmEditor() {
		setTitle("Rhythm Game MIDI Editor");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(800, 1000);

		initializeComponents();

		// 샘플 MIDI 로드 (파일이 있다면 경로 수정)
		loadMidiFile("input.mid");

		// 스크롤 테스트 시작
		startAutoScroll();

		setLocationRelativeTo(null);
		setVisible(true);
	}

	private void initializeComponents() {
		// 1. 테이블 모델 설정 (데이터 보관용)
		tableModel = new DefaultTableModel(500, COLUMN_COUNT + 100); // 넉넉하게 생성
		table = new JTable(tableModel);
		table.setRowHeight(20);
		table.setBackground(Color.BLACK);
		table.setGridColor(Color.DARK_GRAY);
		table.setSelectionBackground(new Color(100, 100, 100, 100));
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		// 2. 컬럼 설정 및 렌더러 적용
		setupColumns();

		// 3. 스크롤 패널 및 레이아웃
		scrollPane = new JScrollPane(table);
		scrollPane.getViewport().setBackground(Color.BLACK);
		add(scrollPane, BorderLayout.CENTER);

		// 4. 행 헤더(Row Number) 설정
		setupRowHeader();
	}

	private void setupColumns() {
		String[] headers = { "SCR", "S", "D", "F", "SPACE", "J", "K", "L" };
		TableColumnModel cm = table.getColumnModel();

		for (int i = 0; i < cm.getColumnCount(); i++) {
			final int colIndex = i;
			cm.getColumn(i).setPreferredWidth(i < 8 ? 50 : 30);
			if (i < headers.length) {
				cm.getColumn(i).setHeaderValue(headers[i]);
			}

			// 노드 렌더러 설정
			cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
				@Override
				public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
						boolean hasFocus, int row, int column) {
					Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

					// 값이 있으면 노드로 간주하여 색상 표시
					if (value != null && !value.toString().isEmpty()) {
						c.setBackground(getNoteColor(column));
						c.setForeground(Color.WHITE);
					} else {
						c.setBackground(Color.BLACK);
						c.setForeground(Color.BLACK);
					}

					// 그리드 선 강조
					setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
					return c;
				}
			});
		}
	}

	private Color getNoteColor(int col) {
		switch (col) {
		case 0:
			return Color.PINK; // SCR
		case 4:
			return Color.YELLOW; // SPACE
		default:
			return Color.CYAN; // 기타 키
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

	// MIDI 파일을 읽어 테이블에 데이터를 채우는 핵심 로직
	public void loadMidiFile(String filePath) {
		try {
			Sequence sequence = MidiSystem.getSequence(new File(filePath));
			// MIDI 길이에 맞춰 테이블 행 수 조절
			int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 100;
			tableModel.setRowCount(totalRows);

			for (Track track : sequence.getTracks()) {
				for (int i = 0; i < track.size(); i++) {
					MidiEvent event = track.get(i);
					MidiMessage message = event.getMessage();

					if (message instanceof ShortMessage) {
						ShortMessage sm = (ShortMessage) message;
						if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0) {
							int pitch = sm.getData1();
							long tick = event.getTick();

							int row = (int) (tick / TICKS_PER_ROW);
							int col = mapPitchToColumn(pitch);

							// 테이블은 아래가 0이므로 역순으로 표시하고 싶다면:
							int actualRow = tableModel.getRowCount() - 1 - row;
							if (actualRow >= 0) {
								tableModel.setValueAt("■", actualRow, col);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "MIDI 로드 실패: " + e.getMessage());
		}
	}

	// MIDI 피치를 8키 컬럼으로 매핑 (사용자 정의 로직)
	private int mapPitchToColumn(int pitch) {
		// 예시: 옥타브 상관없이 피치 모듈러 연산으로 분배
		return pitch % 8;
	}

	private void startAutoScroll() {
		Thread scrollThread = new Thread(() -> {
			int currentRow = table.getRowCount() - 1;
			try {
				while (currentRow >= 0) {
					final int row = currentRow--;
					SwingUtilities.invokeLater(() -> {
						table.changeSelection(row, 0, false, false);
						// 현재 행이 화면 중앙에 오도록 스크롤 조정 가능
					});
					Thread.sleep(10); // BPM에 맞춰 계산 필요
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		scrollThread.start();
	}

	public static void main(String[] args) {
		// UI 스케일링 설정
		System.setProperty("sun.java2d.uiScale", "1.0");
		SwingUtilities.invokeLater(MidiRhythmEditor::new);
	}
}