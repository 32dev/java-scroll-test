package ai08;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import javax.sound.midi.*;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;

public class MidiRhythmEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private JScrollPane scrollPane;

    private final int TICKS_PER_ROW = 20; 
    private final int COLUMN_COUNT = 8; 

    private final String[] NOTE_NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    private double currentBPM = 120.0;
    private int resolution = 480;

    public MidiRhythmEditor() {
        setTitle("Rhythm Game Editor (Save to TXT)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 1000);

        initializeComponents();
        loadMidiFile("input.mid");

        setLocationRelativeTo(null);
        setVisible(true);
        
        SwingUtilities.invokeLater(() -> {
            if (table.getRowCount() > 0) {
                int lastRow = table.getRowCount() - 1;
                table.changeSelection(lastRow, 0, false, false);
            }
        });
    }

    private void initializeComponents() {
        tableModel = new DefaultTableModel(0, COLUMN_COUNT) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(28);
        table.setBackground(Color.BLACK);
        table.setGridColor(new Color(50, 50, 50));
        table.setSelectionBackground(new Color(255, 255, 255, 40));
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
            cm.getColumn(i).setPreferredWidth(i == 4 ? 90 : 65);
            if (i < headers.length) cm.getColumn(i).setHeaderValue(headers[i]);

            cm.getColumn(i).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                        boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value instanceof NoteData) {
                        NoteData data = (NoteData) value;
                        int octave = (data.pitch / 12) - 1;
                        String noteName = NOTE_NAMES[data.pitch % 12];
                        c.setBackground(getOctaveColor(column, octave));
                        setText(noteName + octave);
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setFont(new Font("SansSerif", Font.BOLD, 11));
                        setForeground(octave > 5 ? Color.BLACK : Color.WHITE);
                    } else {
                        c.setBackground(Color.BLACK);
                        setText("");
                    }
                    ((JComponent) c).setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.DARK_GRAY));
                    return c;
                }
            });
        }
    }

    private Color getOctaveColor(int col, int octave) {
        Color base;
        switch (col) {
            case 0: base = Color.PINK; break;
            case 4: base = Color.YELLOW; break;
            default: base = Color.CYAN; break;
        }
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float brightness = 0.3f + (Math.max(0, octave) * 0.1f);
        if (brightness > 1.0f) brightness = 1.0f;
        return Color.getHSBColor(hsb[0], hsb[1], brightness);
    }

    private void setupRowHeader() {
        JList<String> rowHeader = new JList<>(new AbstractListModel<String>() {
            public int getSize() { return table.getRowCount(); }
            public String getElementAt(int index) { return String.valueOf(table.getRowCount() - index); }
        });
        rowHeader.setFixedCellHeight(table.getRowHeight());
        rowHeader.setFixedCellWidth(50);
        rowHeader.setBackground(new Color(30, 30, 30));
        rowHeader.setForeground(Color.LIGHT_GRAY);
        scrollPane.setRowHeaderView(rowHeader);
    }

    public void loadMidiFile(String filePath) {
        StringBuilder sb = new StringBuilder(); // 파일 저장용 데이터 버퍼
        try {
            File file = new File(filePath);
            if (!file.exists()) return;

            Sequence sequence = MidiSystem.getSequence(file);
            this.resolution = sequence.getResolution();

            int totalRows = (int) (sequence.getTickLength() / TICKS_PER_ROW) + 150;
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
                            
                            // 텍스트 형식 구성 및 저장용 빌더에 추가
                            String dataLine = "{\"seq\":" + pitch + ", \"t\":" + (row * 10) + " }, ";
                            sb.append(dataLine).append("\n");
                            System.out.println(dataLine); // 콘솔 출력 유지

                            int actualRow = tableModel.getRowCount() - 1 - row;
                            if (actualRow >= 0) {
                                int targetCol = (pitch % 12) % COLUMN_COUNT;
                                int finalCol = findEmptyColumn(actualRow, targetCol);
                                if (finalCol != -1) {
                                    tableModel.setValueAt(new NoteData(pitch, tick), actualRow, finalCol);
                                }
                            }
                        }
                    }
                }
            }
            
            // 파일로 저장
            saveToFile(sb.toString());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveToFile(String content) {
        try (PrintWriter out = new PrintWriter(new FileWriter("output.txt"))) {
            out.print(content);
            System.out.println("----- 저장 완료: output.txt -----");
        } catch (Exception e) {
            System.err.println("파일 저장 중 오류 발생: " + e.getMessage());
        }
    }

    private int findEmptyColumn(int row, int targetCol) {
        if (tableModel.getValueAt(row, targetCol) == null) return targetCol;
        for (int i = 1; i < COLUMN_COUNT; i++) {
            int right = targetCol + i;
            if (right < COLUMN_COUNT && tableModel.getValueAt(row, right) == null) return right;
            int left = targetCol - i;
            if (left >= 0 && tableModel.getValueAt(row, left) == null) return left;
        }
        return -1;
    }

    static class NoteData {
        int pitch;
        long tick;
        NoteData(int pitch, long tick) {
            this.pitch = pitch;
            this.tick = tick;
        }
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        SwingUtilities.invokeLater(MidiRhythmEditor::new);
    }
}