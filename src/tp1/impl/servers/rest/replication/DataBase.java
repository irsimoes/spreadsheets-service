package tp1.impl.servers.rest.replication;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import tp1.api.Spreadsheet;

public class DataBase {

    private Map<String, Spreadsheet> sheets = new HashMap<String, Spreadsheet>();
    private Map<String, Set<String>> userSheets = new HashMap<String, Set<String>>();

    public DataBase() {
    }

    public DataBase(Map<String, Set<String>> userSheets, Map<String, Spreadsheet> sheets) {
        this.sheets = sheets;
        this.userSheets = userSheets;
    }

    public Map<String, Spreadsheet> getDataBaseSheets() {
        return sheets;
    }

    public Map<String, Set<String>> getDataBaseUserSheets() {
        return userSheets;
    }

    public void setDataBaseSheets(Map<String, Spreadsheet> sheets) {
        this.sheets = sheets;
    }

    public void setDataBaseUserSheets(Map<String, Set<String>> userSheets) {
        this.userSheets = userSheets;
    }
}
