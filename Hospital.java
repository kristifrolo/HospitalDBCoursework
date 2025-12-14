import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class Hospital {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/hospital_db";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "12345";

    private JFrame frame;
    private JTable table;
    private DefaultTableModel tableModel;
    private String currentTable = "patients";

    // UI controls for filters/sort
    private JTextField filterValueField;
    private JComboBox<String> filterColumnCombo;
    private JComboBox<String> sortColumnCombo;
    private JComboBox<String> sortOrderCombo;

    // Current query state
    private String currentFilterColumn = "";
    private String currentFilterValue = "";
    private String currentSortColumn = "";
    private String currentSortOrder = "ASC";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Hospital().createAndShowGUI());
    }

    private void createAndShowGUI() {
        frame = new JFrame("Система управления больницей");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 700);

        // === Top panel: table selector ===
        JPanel topPanel = new JPanel(new FlowLayout());
        String[] tables = {"hospitals", "departments", "positions", "doctors", "patients", "diagnoses", "appointments"};
        for (String tbl : tables) {
            JButton btn = new JButton(capitalize(tbl));
            btn.addActionListener(e -> {
                this.currentTable = tbl;
                resetFilters();
                refreshTable();
            });
            topPanel.add(btn);
        }

        // === Main table ===
        tableModel = new DefaultTableModel();
        table = new JTable(tableModel);
        JScrollPane scrollPane = new JScrollPane(table);

        // === Bottom panel: actions + filters ===
        JPanel bottomPanel = new JPanel(new BorderLayout());

        // Action buttons
        JPanel actionPanel = new JPanel(new FlowLayout());
        JButton btnAdd = new JButton("Добавить");
        JButton btnEdit = new JButton("Изменить");
        JButton btnDelete = new JButton("Удалить");
        JButton btnRefresh = new JButton("Обновить");

        btnAdd.addActionListener(e -> openRecordEditor(null));
        btnEdit.addActionListener(e -> openRecordEditor(getSelectedRowData()));
        btnDelete.addActionListener(this::deleteSelectedRecord);
        btnRefresh.addActionListener(e -> refreshTable());

        actionPanel.add(btnAdd);
        actionPanel.add(btnEdit);
        actionPanel.add(btnDelete);
        actionPanel.add(btnRefresh);
        bottomPanel.add(actionPanel, BorderLayout.NORTH);

        // Filter & sort controls
        JPanel filterPanel = new JPanel(new GridLayout(2, 5, 5, 5));

        filterPanel.add(new JLabel("Фильтр по полю:"));
        List<String> cols = getTableColumns(currentTable);
        filterColumnCombo = new JComboBox<>(cols.toArray(new String[0]));
        if (!cols.isEmpty()) filterColumnCombo.setSelectedIndex(Math.min(1, cols.size() - 1));
        filterPanel.add(filterColumnCombo);

        filterPanel.add(new JLabel("Значение:"));
        filterValueField = new JTextField(15);
        filterPanel.add(filterValueField);

        JButton btnApplyFilter = new JButton("Применить");
        JButton btnClearFilter = new JButton("Сбросить");
        btnApplyFilter.addActionListener(e -> applyFilter());
        btnClearFilter.addActionListener(e -> resetFilters());
        filterPanel.add(btnApplyFilter);
        filterPanel.add(btnClearFilter);

        filterPanel.add(new JLabel("Сортировка по:"));
        sortColumnCombo = new JComboBox<>(cols.toArray(new String[0]));
        if (!cols.isEmpty()) sortColumnCombo.setSelectedIndex(0);
        filterPanel.add(sortColumnCombo);

        filterPanel.add(new JLabel("Порядок:"));
        sortOrderCombo = new JComboBox<>(new String[]{"ASC", "DESC"});
        filterPanel.add(sortOrderCombo);

        JButton btnApplySort = new JButton("Сортировать");
        btnApplySort.addActionListener(e -> applySort());
        filterPanel.add(btnApplySort);

        bottomPanel.add(filterPanel, BorderLayout.CENTER);

        // === Layout: main + bottom + REPORTS BELOW ===
        frame.setLayout(new BorderLayout());
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(scrollPane, BorderLayout.CENTER);

        // Создаём объединённую нижнюю панель
        JPanel fullBottomPanel = new JPanel();
        fullBottomPanel.setLayout(new BoxLayout(fullBottomPanel, BoxLayout.Y_AXIS));
        fullBottomPanel.add(bottomPanel);
        fullBottomPanel.add(Box.createVerticalStrut(10));
        fullBottomPanel.add(createReportsPanel());  // ← отчёты НИЖЕ фильтров

        frame.add(fullBottomPanel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        refreshTable(); // initial load
    }

    // ————————————————————————————————————————————————
    // Helpers
    // ————————————————————————————————————————————————

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).replace('_', ' ');
    }

    private List<String> getTableColumns(String table) {
        List<String> cols = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return cols;
    }

    private List<ColumnInfo> getTableStructure(String table) {
        List<ColumnInfo> cols = new ArrayList<>();
        String sql = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    cols.add(new ColumnInfo(rs.getString(1), rs.getString(2).toLowerCase()));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return cols;
    }

    // ————————————————————————————————————————————————
    // Data loading and filtering
    // ————————————————————————————————————————————————

    private void refreshTable() {
        tableModel.setRowCount(0);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(currentTable);

        if (!currentFilterColumn.isEmpty() && !currentFilterValue.isEmpty()) {
            sql.append(" WHERE ").append(currentFilterColumn).append("::TEXT ILIKE ?");
        }
        if (!currentSortColumn.isEmpty()) {
            sql.append(" ORDER BY ").append(currentSortColumn).append(" ").append(currentSortOrder);
        }

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            if (!currentFilterColumn.isEmpty() && !currentFilterValue.isEmpty()) {
                stmt.setString(1, "%" + currentFilterValue + "%");
            }

            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int cnt = metaData.getColumnCount();

                Vector<String> names = new Vector<>();
                for (int i = 1; i <= cnt; i++) names.add(metaData.getColumnName(i));

                Vector<Vector<Object>> data = new Vector<>();
                while (rs.next()) {
                    Vector<Object> row = new Vector<>();
                    for (int i = 1; i <= cnt; i++) row.add(rs.getObject(i));
                    data.add(row);
                }
                tableModel.setDataVector(data, names);
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(frame, "Ошибка БД:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private void applyFilter() {
        currentFilterColumn = (String) filterColumnCombo.getSelectedItem();
        currentFilterValue = filterValueField.getText().trim();
        refreshTable();
    }

    private void applySort() {
        currentSortColumn = (String) sortColumnCombo.getSelectedItem();
        currentSortOrder = (String) sortOrderCombo.getSelectedItem();
        refreshTable();
    }

    private void resetFilters() {
        currentFilterColumn = "";
        currentFilterValue = "";
        currentSortColumn = "";
        currentSortOrder = "ASC";

        List<String> cols = getTableColumns(currentTable);
        filterColumnCombo.setModel(new DefaultComboBoxModel<>(cols.toArray(new String[0])));
        sortColumnCombo.setModel(new DefaultComboBoxModel<>(cols.toArray(new String[0])));
        if (!cols.isEmpty()) {
            filterColumnCombo.setSelectedIndex(Math.min(1, cols.size() - 1));
            sortColumnCombo.setSelectedIndex(0);
        }

        filterValueField.setText("");
        sortOrderCombo.setSelectedItem("ASC");
        refreshTable();
    }

    // ————————————————————————————————————————————————
    // CRUD
    // ————————————————————————————————————————————————

    private void openRecordEditor(Map<String, Object> initialData) {
        List<ColumnInfo> cols = getTableStructure(currentTable);
        if (cols.isEmpty()) return;

        String pkCol = cols.get(0).name;
        List<ColumnInfo> editable = new ArrayList<>(cols);
        editable.remove(0);

        Map<String, ForeignKeyInfo> fks = getForeignKeys(currentTable);

        RecordEditorDialog dialog = new RecordEditorDialog(frame, "Редактирование: " + currentTable,
                editable, fks, initialData, pkCol);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            Map<String, Object> data = dialog.getData();
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
                conn.setAutoCommit(false);
                boolean success = false;
                if (initialData == null) {
                    String colsStr = String.join(", ", data.keySet());
                    String ph = String.join(", ", Collections.nCopies(data.size(), "?"));
                    String sql = "INSERT INTO " + currentTable + " (" + colsStr + ") VALUES (" + ph + ")";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        int i = 1;
                        for (Object v : data.values()) setParam(stmt, i++, v);
                        stmt.executeUpdate();
                        success = true;
                    }
                } else {
                    String set = String.join(" = ?, ", data.keySet()) + " = ?";
                    String sql = "UPDATE " + currentTable + " SET " + set + " WHERE " + pkCol + " = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        int i = 1;
                        for (Object v : data.values()) setParam(stmt, i++, v);
                        stmt.setObject(i, initialData.get(pkCol));
                        stmt.executeUpdate();
                        success = true;
                    }
                }
                if (success) {
                    conn.commit();
                    JOptionPane.showMessageDialog(frame, initialData == null ? "Добавлено." : "Обновлено.");
                    refreshTable();
                } else throw new SQLException("No rows affected");
            } catch (SQLException e) {
                JOptionPane.showMessageDialog(frame, "Ошибка БД:\n" + e.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        }
    }

    private void setParam(PreparedStatement stmt, int idx, Object val) throws SQLException {
        if (val instanceof java.sql.Date) {
            stmt.setDate(idx, (java.sql.Date) val);
        } else if (val instanceof java.sql.Timestamp) {
            stmt.setTimestamp(idx, (java.sql.Timestamp) val);
        } else {
            stmt.setObject(idx, val);
        }
    }

    private void deleteSelectedRecord(ActionEvent e) {
        int[] sel = table.getSelectedRows();
        if (sel.length == 0) {
            JOptionPane.showMessageDialog(frame, "Выберите строку.", "Внимание", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.NO_OPTION == JOptionPane.showConfirmDialog(frame,
                "Удалить " + sel.length + " запись(ей)?", "Подтверждение", JOptionPane.YES_NO_OPTION)) return;

        String pk = getTableStructure(currentTable).get(0).name;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            conn.setAutoCommit(false);
            String sql = "DELETE FROM " + currentTable + " WHERE " + pk + " = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int r : sel) {
                    Object id = tableModel.getValueAt(table.convertRowIndexToModel(r), 0);
                    stmt.setObject(1, id);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
                JOptionPane.showMessageDialog(frame, "Удалено.");
                refreshTable();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(frame, "Ошибка: " + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    private Map<String, Object> getSelectedRowData() {
        int r = table.getSelectedRow();
        if (r == -1) return null;
        r = table.convertRowIndexToModel(r);
        List<ColumnInfo> cols = getTableStructure(currentTable);
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            data.put(cols.get(i).name, tableModel.getValueAt(r, i));
        }
        return data;
    }

    // ————————————————————————————————————————————————
    // Meta FK
    // ————————————————————————————————————————————————

    private static class ColumnInfo {
        String name, type;
        ColumnInfo(String name, String type) { this.name = name; this.type = type; }
    }

    private static class ForeignKeyInfo {
        String refTable, refPK, displayColumn;
        ForeignKeyInfo(String refTable, String refPK, String displayColumn) {
            this.refTable = refTable; this.refPK = refPK; this.displayColumn = displayColumn;
        }
    }

    private Map<String, ForeignKeyInfo> getForeignKeys(String table) {
        Map<String, ForeignKeyInfo> map = new HashMap<>();
        String sql = """
            SELECT
                kcu.column_name,
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
            JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ?
            """;
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("column_name");
                    String rt = rs.getString("foreign_table_name");
                    String rp = rs.getString("foreign_column_name");
                    String dc = getDisplayColumnForTable(rt);
                    map.put(col, new ForeignKeyInfo(rt, rp, dc));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return map;
    }

    private String getDisplayColumnForTable(String tableName) {
        switch (tableName) {
            case "hospitals":
                return "name";
            case "departments":
                return "name";
            case "positions":
                return "title";
            case "doctors":
                return "surname || ' ' || name || ' ' || COALESCE(patronymic || '.', '')";
            case "patients":
                return "surname || ' ' || name";
            case "diagnoses":
                return "name";
            default:
                return "name";
        }
    }

    // ————————————————————————————————————————————————
    // Record Editor Dialog
    // ————————————————————————————————————————————————

    private static class RecordEditorDialog extends JDialog {
        private final List<JComponent> editors = new ArrayList<>();
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final List<ColumnInfo> columns;
        private boolean confirmed = false;

        public RecordEditorDialog(JFrame owner, String title, List<ColumnInfo> columns,
                                  Map<String, ForeignKeyInfo> foreignKeys,
                                  Map<String, Object> initialData,
                                  String pkCol) {
            super(owner, title, true);
            this.columns = columns;
            setLayout(new BorderLayout());

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;

            int row = 0;
            for (ColumnInfo col : columns) {
                JLabel lbl = new JLabel(capitalize(col.name) + ":");
                gbc.gridx = 0; gbc.gridy = row;
                form.add(lbl, gbc);

                JComponent ed;
                ForeignKeyInfo fk = foreignKeys.get(col.name);
                if (fk != null) {
                    ed = loadFKComboBox(fk);
                } else if ("date".equals(col.type)) {
                    ed = new JTextField(10);
                    ed.setToolTipText("Формат: ГГГГ-ММ-ДД");
                } else if ("timestamp with time zone".equals(col.type) || "timestamptz".equals(col.type)) {
                    ed = new JTextField(20);
                    ed.setToolTipText("Формат: ГГГГ-ММ-ДД ЧЧ:МИ:СС+03 (напр. 2025-12-01 14:30:00+03)");
                } else {
                    ed = new JTextField(20);
                }
                editors.add(ed);
                gbc.gridx = 1; gbc.weightx = 1.0;
                form.add(ed, gbc);

                if (initialData != null && initialData.containsKey(col.name)) {
                    Object val = initialData.get(col.name);
                    if (ed instanceof JComboBox) {
                        JComboBox<?> cb = (JComboBox<?>) ed;
                        for (int i = 0; i < cb.getItemCount(); i++) {
                            FKItem item = (FKItem) cb.getItemAt(i);
                            if (Objects.equals(item.id, val)) {
                                cb.setSelectedIndex(i);
                                break;
                            }
                        }
                    } else if (ed instanceof JTextField tf) {
                        tf.setText(val == null ? "" : val.toString());
                    }
                }
                row++;
            }

            JPanel btns = new JPanel(new FlowLayout());
            JButton ok = new JButton("Сохранить");
            JButton cancel = new JButton("Отмена");
            ok.addActionListener(e -> {
                confirmed = true;
                fillData(foreignKeys);
                if (confirmed) dispose();
            });
            cancel.addActionListener(e -> dispose());
            btns.add(ok); btns.add(cancel);

            add(new JScrollPane(form), BorderLayout.CENTER);
            add(btns, BorderLayout.SOUTH);

            pack();
            setMinimumSize(new Dimension(450, 200));
            setLocationRelativeTo(owner);
        }

        private JComboBox<FKItem> loadFKComboBox(ForeignKeyInfo fk) {
            JComboBox<FKItem> cb = new JComboBox<>();
            cb.addItem(new FKItem(null, "-- не выбрано --"));
            String sql = "SELECT " + fk.refPK + ", " + fk.displayColumn + " FROM " + fk.refTable + " ORDER BY " + fk.displayColumn;
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    Object id = rs.getObject(1);
                    String name = rs.getString(2);
                    cb.addItem(new FKItem(id, name == null ? "(null)" : name));
                }
            } catch (SQLException e) {
                e.printStackTrace();
                cb.addItem(new FKItem(null, "Ошибка загрузки"));
            }
            return cb;
        }

        private void fillData(Map<String, ForeignKeyInfo> foreignKeys) {
            data.clear();
            for (int i = 0; i < columns.size(); i++) {
                ColumnInfo col = columns.get(i);
                JComponent ed = editors.get(i);
                Object val = null;

                if (ed instanceof JComboBox) {
                    FKItem item = (FKItem) ((JComboBox<?>) ed).getSelectedItem();
                    val = item.id;
                } else if (ed instanceof JTextField tf) {
                    String s = tf.getText().trim();
                    if (!s.isEmpty()) {
                        if ("date".equals(col.type)) {
                            try {
                                val = java.sql.Date.valueOf(s);
                            } catch (Exception ex) {
                                err("Неверный формат даты: " + s + ". Используйте ГГГГ-ММ-ДД.");
                                return;
                            }
                        } else if ("timestamp with time zone".equals(col.type) || "timestamptz".equals(col.type)) {
                            try {
                                s = s.replace(" ", "T");
                                if (!s.contains("T")) s += "T00:00:00";
                                if (!s.contains("+") && !s.contains("Z")) s += "+03:00";
                                Instant inst = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s));
                                val = java.sql.Timestamp.from(inst);
                            } catch (Exception ex) {
                                err("Неверный формат времени. Пример: 2025-12-01 14:30:00+03");
                                return;
                            }
                        } else {
                            val = s;
                        }
                    }
                }
                data.put(col.name, val);
            }
        }

        private void err(String msg) {
            JOptionPane.showMessageDialog(this, msg, "Ошибка ввода", JOptionPane.ERROR_MESSAGE);
            confirmed = false;
        }

        public boolean isConfirmed() { return confirmed; }
        public Map<String, Object> getData() { return data; }
    }

    private static class FKItem {
        final Object id;
        final String label;
        FKItem(Object id, String label) {
            this.id = id;
            this.label = label;
        }
        @Override public String toString() {
            return label;
        }
    }

    // ————————————————————————————————————————————————
    // Отчёты
    // ————————————————————————————————————————————————

    private JPanel createReportsPanel() {
        JPanel reportPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        reportPanel.setBorder(BorderFactory.createTitledBorder("Отчёты"));

        JLabel lbl = new JLabel("Отчёт:");
        JComboBox<String> reportCombo = new JComboBox<>(new String[]{
                "— не выбран —",
                "Количество врачей по отделениям",
                "Отработанные приёмы по врачам",
                "Демография пациентов"
        });
        JButton btnGen = new JButton("Сформировать");
        btnGen.addActionListener(e -> {
            String sel = (String) reportCombo.getSelectedItem();
            if ("— не выбран —".equals(sel) || sel == null) {
                JOptionPane.showMessageDialog(frame, "Выберите отчёт.", "Внимание", JOptionPane.WARNING_MESSAGE);
                return;
            }
            showReportDialog(sel);
        });

        reportPanel.add(lbl);
        reportPanel.add(reportCombo);
        reportPanel.add(btnGen);
        return reportPanel;
    }

    private void showReportDialog(String reportName) {
        switch (reportName) {
            case "Количество врачей по отделениям" -> showDoctorsCountReport();
            case "Отработанные приёмы по врачам" -> showAppointmentsByDoctorReport();
            case "Демография пациентов" -> showPatientDemographicsReport();
        }
    }

    // ——— 1 Отчёт: Врачи по отделениям ———
    private void showDoctorsCountReport() {
        JDialog dlg = new JDialog(frame, "Параметры отчёта: Врачи по отделениям", true);
        dlg.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5,5,5,5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;
        JLabel l1 = new JLabel("Больница (оставьте пустым — все):");
        JTextField hospitalFilter = new JTextField(20);
        gbc.gridx = 0; gbc.gridy = y; form.add(l1, gbc);
        gbc.gridx = 1; form.add(hospitalFilter, gbc); y++;

        JLabel l2 = new JLabel("Сортировка:");
        JComboBox<String> sortCombo = new JComboBox<>(new String[]{"по больнице", "по количеству врачей"});
        gbc.gridx = 0; gbc.gridy = y; form.add(l2, gbc);
        gbc.gridx = 1; form.add(sortCombo, gbc); y++;

        JPanel btns = new JPanel(new FlowLayout());
        JButton ok = new JButton("Сформировать");
        JButton cancel = new JButton("Отмена");
        ok.addActionListener(e -> {
            Map<String, Object> params = new HashMap<>();
            params.put("hospital", hospitalFilter.getText().trim());
            params.put("sortBy", sortCombo.getSelectedItem());
            generateDoctorsCountReport(params);
            dlg.dispose();
        });
        cancel.addActionListener(e -> dlg.dispose());
        btns.add(ok); btns.add(cancel);

        dlg.add(new JScrollPane(form), BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.setSize(500, 180);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    private void generateDoctorsCountReport(Map<String, Object> params) {
        String hospital = (String) params.get("hospital");
        String sortBy = (String) params.get("sortBy");

        StringBuilder sql = new StringBuilder("""
            SELECT
                h.name AS hospital,
                d.name AS department,
                COUNT(doc.doctor_id) AS doctor_count
            FROM departments d
            JOIN hospitals h USING (hospital_id)
            LEFT JOIN doctors doc ON d.department_id = doc.department_id
            """);
        if (!hospital.isEmpty()) {
            sql.append(" WHERE h.name ILIKE ? ");
        }
        sql.append(" GROUP BY h.hospital_id, h.name, d.department_id, d.name ");

        if ("по количеству врачей".equals(sortBy)) {
            sql.append(" ORDER BY doctor_count DESC, h.name, d.name ");
        } else {
            sql.append(" ORDER BY h.name, d.name ");
        }

        // Запрос итогов
        String totalSql = """
            SELECT
                h.name AS hospital,
                COUNT(doc.doctor_id) AS total
            FROM hospitals h
            LEFT JOIN departments d ON h.hospital_id = d.hospital_id
            LEFT JOIN doctors doc ON d.department_id = doc.department_id
            """;
        if (!hospital.isEmpty()) totalSql += " WHERE h.name ILIKE ? ";
        totalSql += " GROUP BY h.hospital_id, h.name ORDER BY h.name";

        String grandTotalSql = """
            SELECT COUNT(*) AS cnt FROM doctors
            """;
        if (!hospital.isEmpty()) grandTotalSql = """
            SELECT COUNT(*) AS cnt
            FROM doctors doc
            JOIN departments d ON doc.department_id = d.department_id
            JOIN hospitals h ON d.hospital_id = h.hospital_id
            WHERE h.name ILIKE ?
            """;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            Vector<String> cols = new Vector<>(List.of("Больница", "Отделение", "Врачей"));
            Vector<Vector<Object>> rows = new Vector<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                if (!hospital.isEmpty()) stmt.setString(1, "%" + hospital + "%");
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Vector<Object> r = new Vector<>();
                        r.add(rs.getString("hospital"));
                        r.add(rs.getString("department"));
                        r.add(rs.getInt("doctor_count"));
                        rows.add(r);
                    }
                }
            }

            // Добавляем подитоги
            try (PreparedStatement stmt = conn.prepareStatement(totalSql)) {
                if (!hospital.isEmpty()) stmt.setString(1, "%" + hospital + "%");
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Vector<Object> r = new Vector<>();
                        r.add("→ ИТОГО по " + rs.getString("hospital"));
                        r.add("");
                        r.add(rs.getInt("total"));
                        r.set(0, "<html><b>" + r.get(0) + "</b></html>");
                        rows.add(r);
                    }
                }
            }

            // Глобальный итог
            int grandTotal;
            try (PreparedStatement stmt = conn.prepareStatement(grandTotalSql)) {
                if (!hospital.isEmpty()) stmt.setString(1, "%" + hospital + "%");
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    grandTotal = rs.getInt("cnt");
                }
            }

            Vector<Object> r = new Vector<>();
            r.add("<html><b>→ ОБЩИЙ ИТОГ</b></html>");
            r.add("");
            r.add(grandTotal);
            rows.add(r);

            showReportInDialog("Отчёт: Врачи по отделениям", cols, rows);
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Ошибка генерации отчёта:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ——— 2. Отчёт: Приёмы по врачам ———
    private void showAppointmentsByDoctorReport() {
        JDialog dlg = new JDialog(frame, "Параметры отчёта: Приёмы по врачам", true);
        dlg.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5,5,5,5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;
        JLabel l1 = new JLabel("Статус приёма:");
        JComboBox<String> statusCombo = new JComboBox<>(new String[]{"любой", "completed", "scheduled", "cancelled"});
        gbc.gridx = 0; gbc.gridy = y; form.add(l1, gbc);
        gbc.gridx = 1; form.add(statusCombo, gbc); y++;

        JLabel l2 = new JLabel("Период с (ГГГГ-ММ-ДД):");
        JTextField fromField = new JTextField(10);
        fromField.setText("2025-01-01");
        JLabel l3 = new JLabel("по:");
        JTextField toField = new JTextField(10);
        toField.setText("2025-12-31");
        gbc.gridx = 0; gbc.gridy = y; form.add(l2, gbc);
        gbc.gridx = 1; form.add(fromField, gbc); y++;
        gbc.gridx = 0; gbc.gridy = y; form.add(l3, gbc);
        gbc.gridx = 1; form.add(toField, gbc); y++;

        JLabel l4 = new JLabel("Сортировка:");
        JComboBox<String> sortCombo = new JComboBox<>(new String[]{"по ФИО", "по количеству приёмов", "по средней длительности"});
        gbc.gridx = 0; gbc.gridy = y; form.add(l4, gbc);
        gbc.gridx = 1; form.add(sortCombo, gbc); y++;

        JPanel btns = new JPanel(new FlowLayout());
        JButton ok = new JButton("Сформировать");
        JButton cancel = new JButton("Отмена");
        ok.addActionListener(e -> {
            Map<String, Object> params = new HashMap<>();
            String status = (String) statusCombo.getSelectedItem();
            params.put("status", "любой".equals(status) ? null : status);
            params.put("from", fromField.getText().trim());
            params.put("to", toField.getText().trim());
            params.put("sortBy", sortCombo.getSelectedItem());
            generateAppointmentsByDoctorReport(params);
            dlg.dispose();
        });
        cancel.addActionListener(e -> dlg.dispose());
        btns.add(ok); btns.add(cancel);

        dlg.add(new JScrollPane(form), BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.setSize(500, 220);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    private void generateAppointmentsByDoctorReport(Map<String, Object> params) {
        String status = (String) params.get("status");
        String from = (String) params.get("from");
        String to = (String) params.get("to");
        String sortBy = (String) params.get("sortBy");

        StringBuilder sql = new StringBuilder("""
            SELECT
                d.surname || ' ' || d.name || ' ' || COALESCE(d.patronymic || '.', '') AS doctor,
                COUNT(a.appointment_id) AS appointment_count,
                AVG(EXTRACT(EPOCH FROM (a.appointment_end - a.appointment_start)) / 60)::int AS avg_duration_min,
                SUM(EXTRACT(EPOCH FROM (a.appointment_end - a.appointment_start)) / 60)::int AS total_minutes
            FROM doctors d
            LEFT JOIN appointments a ON d.doctor_id = a.doctor_id
            WHERE a.appointment_start >= ?::timestamptz
              AND a.appointment_start <  ?::timestamptz + INTERVAL '1 day'
            """);
        if (status != null) {
            sql.append(" AND a.status = ? ");
        }
        sql.append("""
            GROUP BY d.doctor_id, d.surname, d.name, d.patronymic
            """);

        switch (sortBy) {
            case "по количеству приёмов" -> sql.append(" ORDER BY appointment_count DESC ");
            case "по средней длительности" -> sql.append(" ORDER BY avg_duration_min DESC ");
            default -> sql.append(" ORDER BY d.surname, d.name ");
        }

        String summarySql = """
            SELECT
                COUNT(a.appointment_id) AS total_appointments,
                SUM(EXTRACT(EPOCH FROM (a.appointment_end - a.appointment_start)) / 60)::int AS total_minutes_all
            FROM appointments a
            WHERE a.appointment_start >= ?::timestamptz
              AND a.appointment_start <  ?::timestamptz + INTERVAL '1 day'
            """;
        if (status != null) summarySql += " AND a.status = ? ";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            Vector<String> cols = new Vector<>(List.of("Врач", "Приёмов", "Ср.длит., мин", "Всего, мин"));
            Vector<Vector<Object>> rows = new Vector<>();

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                stmt.setString(1, from);
                stmt.setString(2, to);
                int idx = 3;
                if (status != null) stmt.setString(idx++, status);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Vector<Object> r = new Vector<>();
                        r.add(rs.getString("doctor"));
                        r.add(rs.getInt("appointment_count"));
                        r.add(rs.getInt("avg_duration_min"));
                        r.add(rs.getInt("total_minutes"));
                        rows.add(r);
                    }
                }
            }

            // Итоговая строка
            try (PreparedStatement stmt = conn.prepareStatement(summarySql)) {
                stmt.setString(1, from);
                stmt.setString(2, to);
                int idx = 3;
                if (status != null) stmt.setString(idx++, status);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Vector<Object> r = new Vector<>();
                        r.add("<html><b>→ ИТОГО</b></html>");
                        r.add(rs.getInt("total_appointments"));
                        r.add(""); // среднее не имеет смысла в итоге — оставим пустым
                        r.add(rs.getInt("total_minutes_all"));
                        rows.add(r);
                    }
                }
            }

            showReportInDialog("Отчёт: Приёмы по врачам", cols, rows);
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Ошибка генерации отчёта:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ——— 3. Отчёт: Демография пациентов ———
    private void showPatientDemographicsReport() {
        JDialog dlg = new JDialog(frame, "Параметры отчёта: Демография пациентов", true);
        dlg.setLayout(new BorderLayout());

        JPanel form = new JPanel(new FlowLayout());
        form.add(new JLabel("Фильтр по полу:"));
        JComboBox<String> genderCombo = new JComboBox<>(new String[]{"все", "мужчины", "женщины"});
        form.add(genderCombo);

        JPanel btns = new JPanel(new FlowLayout());
        JButton ok = new JButton("Сформировать");
        JButton cancel = new JButton("Отмена");
        ok.addActionListener(e -> {
            String gender = (String) genderCombo.getSelectedItem();
            generatePatientDemographicsReport("все".equals(gender) ? null : "мужчины".equals(gender) ? "m" : "f");
            dlg.dispose();
        });
        cancel.addActionListener(e -> dlg.dispose());
        btns.add(ok); btns.add(cancel);

        dlg.add(form, BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.setSize(300, 120);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }

    private void generatePatientDemographicsReport(String gender) {
        // Вычисляем возрастные группы прямо в SQL
        String baseSql = """
            SELECT
                CASE
                    WHEN EXTRACT(YEAR FROM AGE(CURRENT_DATE, birth_date)) < 18 THEN '0–17'
                    WHEN EXTRACT(YEAR FROM AGE(CURRENT_DATE, birth_date)) BETWEEN 18 AND 35 THEN '18–35'
                    WHEN EXTRACT(YEAR FROM AGE(CURRENT_DATE, birth_date)) BETWEEN 36 AND 55 THEN '36–55'
                    ELSE '56+'
                END AS age_group,
                COUNT(*) AS cnt
            FROM patients
            WHERE 1 = 1
            """;
        if (gender != null) baseSql += " AND gender = ? ";
        baseSql += " GROUP BY age_group ORDER BY MIN(EXTRACT(YEAR FROM AGE(CURRENT_DATE, birth_date))) ";

        String totalSql = "SELECT COUNT(*) AS total FROM patients";
        if (gender != null) totalSql += " WHERE gender = ? ";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            int total;
            try (PreparedStatement stmt = conn.prepareStatement(totalSql)) {
                if (gender != null) stmt.setString(1, gender);
                try (ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    total = rs.getInt("total");
                }
            }

            Vector<String> cols = new Vector<>(List.of("Возрастная группа", "Количество", "%"));
            Vector<Vector<Object>> rows = new Vector<>();

            try (PreparedStatement stmt = conn.prepareStatement(baseSql)) {
                if (gender != null) stmt.setString(1, gender);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String group = rs.getString("age_group");
                        int cnt = rs.getInt("cnt");
                        double pct = total == 0 ? 0.0 : (cnt * 100.0 / total);
                        Vector<Object> r = new Vector<>();
                        r.add(group);
                        r.add(cnt);
                        r.add(String.format("%.1f%%", pct));
                        rows.add(r);
                    }
                }
            }

            // Итог
            Vector<Object> r = new Vector<>();
            r.add("<html><b>→ ИТОГО</b></html>");
            r.add(total);
            r.add("100.0%");
            rows.add(r);

            showReportInDialog("Отчёт: Демография пациентов", cols, rows);
        } catch (SQLException ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Ошибка генерации отчёта:\n" + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ——— Общий метод отображения отчёта ———
    private void showReportInDialog(String title, Vector<String> columnNames, Vector<Vector<Object>> data) {
        JDialog dlg = new JDialog(frame, title, true);
        dlg.setLayout(new BorderLayout());

        DefaultTableModel model = new DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable rptTable = new JTable(model);
        rptTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof String && ((String) value).startsWith("<html>")) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        });
        JScrollPane scroll = new JScrollPane(rptTable);

        JButton closeBtn = new JButton("Закрыть");
        closeBtn.addActionListener(e -> dlg.dispose());

        dlg.add(scroll, BorderLayout.CENTER);
        JPanel p = new JPanel(new FlowLayout());
        p.add(closeBtn);
        dlg.add(p, BorderLayout.SOUTH);

        dlg.setSize(600, 400);
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
    }
}