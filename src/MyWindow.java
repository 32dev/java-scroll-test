import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

public class MyWindow extends JFrame {
	private JTable table;
	private JScrollPane scrollPane;

	public MyWindow() {
		setTitle("Editor");
		initializeComponents();
		setupLayout();

		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1920, 1040); // 해상도에 맞춰 적절히 조정
		setLocationRelativeTo(null);
		table.requestFocusInWindow();
		table.setBackground(Color.black);
		int row = table.getRowCount() - 1; // 첫 번째 행
		int col = 0; // 첫 번째 열
		table.changeSelection(row, col, false, false);
		Thread upThread = new Thread() {

			@Override
			public void run() {
				int row = table.getRowCount() - 1; // 첫 번째 행
				try {
					while (true) {
						table.changeSelection(row--, col, false, false);
						Thread.sleep(125);
						if (row < 0) {
							break;
						}

					}
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}

		};
		upThread.start();
		setVisible(true);
	}

	private void initializeComponents() {
		// 1. 테이블 초기화 (100행 26열)
		table = new JTable(192, 26 + 8);
		table.setRowHeight(15);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

		// 2. 컬럼 너비 일괄 설정
		TableColumnModel columnModel = table.getColumnModel();
		for (int i = 0; i < columnModel.getColumnCount(); i++) {
			TableColumn column = columnModel.getColumn(i);
			DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
			renderer.setHorizontalAlignment(DefaultTableCellRenderer.CENTER);
			column.setPreferredWidth(60);
			switch (i) {
			case 0:
				column.setHeaderValue("scr");
				renderer.setBackground(Color.PINK);
				column.setPreferredWidth(50); // 기본 권장 너비

				break;
			case 1:
				column.setHeaderValue("s");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;
			case 2:
				column.setHeaderValue("d");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;
			case 3:
				column.setHeaderValue("f");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;
			case 4:
				column.setHeaderValue("space");
				renderer.setBackground(Color.yellow);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;
			case 5:
				column.setHeaderValue("j");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;
			case 6:
				column.setHeaderValue("k");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(25); // 기본 권장 너비
				break;
			case 7:
				column.setHeaderValue("l");
				renderer.setBackground(Color.DARK_GRAY);
				column.setPreferredWidth(30); // 기본 권장 너비
				break;

			default:
				// 8->
				column.setHeaderValue((char) (i + 57));
				break;

			}
			column.setCellRenderer(renderer);
		}

		// 3. 스크롤 패널 생성
		scrollPane = new JScrollPane(table);

		// 4. 행 번호를 표시할 JList 설정 (Row Header)
		setupRowHeader();
	}

	private void setupRowHeader() {
		JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
			@Override
			public int getSize() {
				return table.getRowCount();
			}

			@Override
			public String getElementAt(int index) {
				// 기존 로직: 역순 표시 (99부터 0까지)
				if (table.getRowCount() - index <= 64) {
					return String.valueOf(" ");
				} else {
					return String.valueOf(table.getRowCount() - index - 64);
				}
			}
		});

		// 행 헤더 스타일 설정
		rowHeader.setFixedCellHeight(table.getRowHeight());
		rowHeader.setFixedCellWidth(50);
		rowHeader.setBackground(new Color(240, 240, 240));
		rowHeader.setSelectionBackground(new Color(240, 240, 240)); // 선택 시 색상 고정

		rowHeader.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
					boolean cellHasFocus) {
				// 기본 렌더러 기능을 호출하여 텍스트 설정을 가져옵니다.
				super.getListCellRendererComponent(list, value, index, false, false);

				setHorizontalAlignment(CENTER); // 가운데 정렬

				// MatteBorder(top, left, bottom, right, color)
				// 아래쪽(bottom)과 오른쪽(right)에 1픽셀 선을 그려 그리드 효과를 줍니다.
				setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.LIGHT_GRAY));

				return this;
			}
		});

		scrollPane.setRowHeaderView(rowHeader);
	}

	private void setupLayout() {
		setLayout(new BorderLayout());
		add(scrollPane, BorderLayout.CENTER);
	}

	public static void main(String[] args) {
		// 실행 시 UI 스케일링 설정 (가장 먼저 실행)
		System.setProperty("sun.java2d.uiScale", "1.0");

		// Swing 컴포넌트는 EDT에서 생성하는 것이 원칙입니다.
		SwingUtilities.invokeLater(() -> new MyWindow());
	}
}