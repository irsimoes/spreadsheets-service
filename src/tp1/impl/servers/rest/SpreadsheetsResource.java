package tp1.impl.servers.rest;

import java.net.InetAddress;
import java.util.*;
import java.net.URI;
import java.net.UnknownHostException;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import tp1.api.Spreadsheet;
import tp1.api.service.rest.*;
import tp1.discovery.Discovery;
import tp1.api.engine.*;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.util.CellRange;

@Singleton
public class SpreadsheetsResource implements RestSpreadsheets {

	public static final int PORT = 8080;
	public static final int MAX_RETRIES = 3;
	public static final long RETRY_PERIOD = 1000;
	public static final int CONNECTION_TIMEOUT = 10000;
	public static final int REPLY_TIMEOUT = 600;

	private final Map<String, Spreadsheet> sheets = new HashMap<String, Spreadsheet>();
	private final Map<String, Set<String>> userSheets = new HashMap<String, Set<String>>();
	private final Map<String, String[][]> cellCache = new HashMap<String, String[][]>();
	private static Discovery discovery;
	private static String domain;
	private static Client client;

	public SpreadsheetsResource() {
	}

	public SpreadsheetsResource(String domain, Discovery discovery) {
		SpreadsheetsResource.discovery = discovery;
		SpreadsheetsResource.domain = domain;

		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
	}

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {
		// TODO Auto-generated method stub

		if (sheet == null || password == null || sheet.getSheetId() != null || sheet.getSheetURL() != null
				|| sheet.getRows() < 0 || sheet.getColumns() < 0) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, sheet.getOwner(), password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		try {
			String id = UUID.randomUUID().toString();
			sheet.setSheetId(id);

			String ip = InetAddress.getLocalHost().getHostAddress();
			String sheetURL = String.format("http://%s:%s/rest/spreadsheets/%s", ip, PORT, id);
			sheet.setSheetURL(sheetURL);

			synchronized (this) {
				sheets.put(id, sheet);

				Set<String> sheets = userSheets.get(sheet.getOwner());
				if (sheets == null) {
					sheets = new HashSet<String>();
				}

				sheets.add(id);
				userSheets.put(sheet.getOwner(), sheets);
			}
		} catch (UnknownHostException e) {
		}

		return sheet.getSheetId();
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {
		// TODO Auto-generated method stub

		if (sheetId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			owner = sheet.getOwner();
		}

		int status = requestUser(domain, owner, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			if (sheet.getOwner().equals(owner)) {
				sheets.remove(sheetId);
			}
		}
	}

	@Override
	public Spreadsheet getSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		Spreadsheet sheet;
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}

			if (!sheet.getOwner().equals(userId)
					&& !(sheet.getSharedWith().contains(String.format("%s@%s", userId, domain)))) {
				throw new WebApplicationException(Status.FORBIDDEN); // 403
			}
		}
		return sheet; // 200
	}

	@Override
	public String[][] getSpreadsheetValues(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		String[][] values;
		Spreadsheet sheet;
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}

			if (!sheet.getOwner().equals(userId)
					&& !(sheet.getSharedWith().contains(String.format("%s@%s", userId, domain)))) {
				throw new WebApplicationException(Status.FORBIDDEN); // 403
			}
		}
		values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
			@Override
			public int rows() {
				return sheet.getRows();
			}

			@Override
			public int columns() {
				return sheet.getColumns();
			}

			@Override
			public String sheetId() {
				return sheet.getSheetId();
			}

			@Override
			public String cellRawValue(int row, int col) {
				try {
					return sheet.getCellRawValue(row, col);
				} catch (IndexOutOfBoundsException e) {
					return "#ERR?";
				}
			}

			@Override
			public String[][] getRangeValues(String sheetURL, String range) {
				String user = String.format("%s@%s", sheet.getOwner(), domain);

				WebTarget target = client.target(sheetURL).path("/range");

				short retries = 0;

				while (retries < MAX_RETRIES) {

					try {
						Response r = target.queryParam("userId", user).queryParam("range", range).request()
								.accept(MediaType.APPLICATION_JSON).get();

						String[][] values;
						if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
							values = r.readEntity(String[][].class);
							cellCache.put(String.format("%s@%s", sheetURL, range), values);

							return values;
						} else {
							values = cellCache.get(String.format("%s@%s", sheetURL, range));
							if(values != null) {
								return values;
							} else {
								throw new WebApplicationException(r.getStatus());
							}
						}

					} catch (ProcessingException pe) {
						retries++;
						try {
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					}
				}
				return cellCache.get(String.format("%s@%s", sheetURL, range));
			}
		});
		return values; // 200
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password) {
		// TODO Auto-generated method stub

		if (sheetId == null || userId == null || password == null || rawValue == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}

			if (!sheet.getOwner().equals(userId)
					&& !(sheet.getSharedWith().contains(String.format("%s@%s", userId, domain)))) {
				throw new WebApplicationException(Status.FORBIDDEN); // 403
			}

			sheet.setCellRawValue(cell, rawValue);
		}
	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub

		if (sheetId == null || userId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[] user = userId.split("@");
		int status2 = requestUser(user[1], user[0], "");
		if (status2 == Status.NOT_FOUND.getStatusCode()) {
			throw new WebApplicationException(status2); // 403 or 404
		}

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			owner = sheet.getOwner();
		}

		int status = requestUser(domain, owner, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			Set<String> sharedWith = sheet.getSharedWith();
			if (sharedWith.contains(userId)) {
				throw new WebApplicationException(Status.CONFLICT); // 409
			}

			sharedWith.add(userId);
			sheet.setSharedWith(sharedWith);
		}
	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[] user = userId.split("@");
		int status2 = requestUser(user[1], user[0], "");
		if (status2 == Status.NOT_FOUND.getStatusCode()) {
			throw new WebApplicationException(status2); // 403 or 404
		}

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			owner = sheet.getOwner();
		}

		int status = requestUser(domain, owner, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			Set<String> sharedWith = sheet.getSharedWith();
			sharedWith.remove(userId);
		}
	}

	@Override
	public String[][] getRange(String sheetId, String userId, String range) {
		// TODO Auto-generated method stub

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[][] rangeValues;
		Spreadsheet sheet;
		
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}

			if (!sheet.getOwner().equals(userId) && !(sheet.getSharedWith().contains(userId))) {
				throw new WebApplicationException(Status.FORBIDDEN); // 403
			}
		}

		CellRange cellRange = new CellRange(range);
		rangeValues = cellRange.extractRangeValuesFrom(sheet.getRawValues());
		
		String[][] values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
			@Override
			public int rows() {
				return cellRange.rows();
			}

			@Override
			public int columns() {
				return cellRange.cols();
			}

			@Override
			public String sheetId() {
				return sheet.getSheetId();
			}

			@Override
			public String cellRawValue(int row, int col) {
				try {
					return rangeValues[row][col];
				} catch (IndexOutOfBoundsException e) {
					return "#ERR?";
				}
			}

			@Override
			public String[][] getRangeValues(String sheetURL, String range) {
				String user = String.format("%s@%s", sheet.getOwner(), domain);

				WebTarget target = client.target(sheetURL).path("/range");

				short retries = 0;

				while (retries < MAX_RETRIES) {

					try {
						Response r = target.queryParam("userId", user).queryParam("range", range).request()
								.accept(MediaType.APPLICATION_JSON).get();
						
						String[][] values;
						if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
							values = r.readEntity(String[][].class);
							cellCache.put(String.format("%s@%s", sheetURL, range), values);

							return values;
						} else {
							values = cellCache.get(String.format("%s@%s", sheetURL, range));
							if(values != null) {
								return values;
							} else {
								throw new WebApplicationException(r.getStatus());
							}
						}

					} catch (ProcessingException pe) {
						retries++;
						try {
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					}
				}
				return null;
			}
		});

		return values;
	}

	@Override
	public void deleteUserSpreadsheets(String userId) {

		if (userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}
		synchronized (this) {
			Set<String> sheetIds = userSheets.get(userId);
			if (sheetIds != null) {
				for (String id : sheetIds) {
					sheets.remove(id);
				}
				userSheets.remove(userId);
			}
		}
	}

	private int requestUser(String userDomain, String userId, String password) {

		URI[] uri = null;
		while (uri == null) {
			try {
				uri = discovery.knownUrisOf(userDomain, "users");
				Thread.sleep(500);
			} catch (Exception e) {
			}
		}

		String serverUrl = uri[0].toString();
		WebTarget target = client.target(serverUrl).path(RestUsers.PATH);

		short retries = 0;

		while (retries < MAX_RETRIES) {

			try {
				Response r = target.path(userId).queryParam("password", password).request()
						.accept(MediaType.APPLICATION_JSON).get();

				return r.getStatus();

			} catch (ProcessingException pe) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException e) {
				}
			}
		}
		return 0;
	}

}