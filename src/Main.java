import java.awt.BorderLayout;
import java.awt.Color;

import javax.swing.AbstractListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
void main() {
    // 1. 실행하자마자 스케일링 기능을 끕니다. (가장 중요)
    System.setProperty("sun.java2d.uiScale", "1.0");
    new MyWindow();
}

public class MyWindow extends JFrame{
	JTable table = null;
	JScrollPane scrollPane =null;
	public MyWindow() {
		table = new JTable(100, 26);
		scrollPane =  new JScrollPane(table);
		table.setRowHeight(20);
		
		// 1. 행 번호를 표시할 JList 생성
        JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
            @Override
            public int getSize() {
                return table.getRowCount(); // 테이블 행 개수만큼 번호 생성
            }
            @Override
            public String getElementAt(int index) {
                return " " + (table.getRowCount() - (index + 1)) + " "; // 표시될 번호 (1부터 시작)
            }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setBackground(new Color(240, 240, 240)); // 연회색 배경
        rowHeader.setCellRenderer(new DefaultListCellRenderer() {
            { setHorizontalAlignment(CENTER); } // 가운데 정렬
        });
        scrollPane.setRowHeaderView(rowHeader);
		
		
		TableColumnModel columnModel = table.getColumnModel();
		for ( int i=0; i<26;i++) {
			TableColumn column = columnModel.getColumn(i);
			column.setPreferredWidth(50);
		}
		for ( int j=0; j<100;j++) {
		}
		
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		setLayout(new BorderLayout());
		add(scrollPane, BorderLayout.CENTER);
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setSize(1920,1080);
		setLocationRelativeTo(null);
		setVisible(true);
	}
}
