package tp1.impl.servers.rest;

import java.util.List;

import tp1.api.Spreadsheet;
import tp1.api.service.rest.RestSpreadsheets;

public class SpreadsheetsResource implements RestSpreadsheets {

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {
		// TODO Auto-generated method stub
		
		return null;
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {
		// TODO Auto-generated method stub

	}

	@Override
	public Spreadsheet getSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<List<String>> getSpreadsheetValues(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password) {
		// TODO Auto-generated method stub

	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub

	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub

	}

}
