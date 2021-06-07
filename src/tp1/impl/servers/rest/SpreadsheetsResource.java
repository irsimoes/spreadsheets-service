package tp1.impl.servers.rest;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.util.*;

import javax.xml.namespace.QName;

import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import com.google.gson.Gson;
import com.sun.xml.ws.client.BindingProviderProperties;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.api.Spreadsheet;
import tp1.api.ValuesResult;
import tp1.api.service.rest.*;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.discovery.Discovery;
import tp1.api.engine.*;
import tp1.api.replies.GoogleSheetValuesReturn;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.impl.servers.soap.SpreadsheetsWS;
import tp1.impl.servers.soap.UsersWS;
import tp1.util.CellRange;

public class SpreadsheetsResource implements RestSpreadsheets {

	public static final int PORT = 8080;
	public static final int MAX_RETRIES = 3;
	public static final long RETRY_PERIOD = 1000;
	public static final int CONNECTION_TIMEOUT = 10000;
	public static final int REPLY_TIMEOUT = 600;
	public static final int CACHE_VALIDITY_TIME = 20000;
	public static final String GOOGLE_SHEETS = "https://sheets.googleapis.com";

	private Map<String, Spreadsheet> sheets = new HashMap<String, Spreadsheet>();
	private Map<String, Set<String>> userSheets = new HashMap<String, Set<String>>();
	private Map<String, ValuesResult> cache = new HashMap<String, ValuesResult>();
	private Map<String, Long> twServer = new HashMap<String, Long>();
	private Map<String, Long> tc = new HashMap<String, Long>();
	private Discovery discovery;
	private String domain, serverSecret, googleKey;
	private Client client;

	public SpreadsheetsResource() {
	}

	public SpreadsheetsResource(String domain, String serverSecret, String googleKey, Discovery discovery) {
		this.discovery = discovery;
		this.domain = domain;
		this.serverSecret = serverSecret;
		this.googleKey = googleKey;

		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
	}

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {

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
			String sheetURL = String.format("https://%s:%s/rest/spreadsheets/%s", ip, PORT, id);
			sheet.setSheetURL(sheetURL);

			synchronized (this) {
				sheets.put(id, sheet);

				Set<String> sheetsSet = userSheets.get(sheet.getOwner());
				if (sheetsSet == null) {
					sheetsSet = new HashSet<String>();
				}

				sheetsSet.add(id);
				userSheets.put(sheet.getOwner(), sheetsSet);
				twServer.put(id, System.currentTimeMillis());
			}
		} catch (UnknownHostException e) {
		}

		return sheet.getSheetId();
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {

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
				userSheets.get(owner).remove(sheetId);
				twServer.remove(sheetId);
			}
		}
	}

	@Override
	public Spreadsheet getSpreadsheet(int version, String sheetId, String userId, String password) {

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}

		if (!sheet.getOwner().equals(userId) && ((sheet.getSharedWith() == null)
				|| (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))) {
			throw new WebApplicationException(Status.FORBIDDEN); // 403
		}

		return sheet; // 200
	}

	@Override
	public String[][] getSpreadsheetValues(int version, String sheetId, String userId, String password) {

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}

		if (!sheet.getOwner().equals(userId) && ((sheet.getSharedWith() == null)
				|| (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))) {
			throw new WebApplicationException(Status.FORBIDDEN); // 403
		}

		String[][] values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
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
				return importRangeValues(sheetURL, range, sheet.getOwner());
			}
		});
		return values; // 200
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password) {

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

			if (!sheet.getOwner().equals(userId) && ((sheet.getSharedWith() == null)
					|| (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))) {
				throw new WebApplicationException(Status.FORBIDDEN); // 403
			}

			sheet.setCellRawValue(cell, rawValue);
			twServer.put(sheet.getSheetId(), System.currentTimeMillis());
		}
	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {

		if (sheetId == null || userId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[] user = userId.split("@");
		int status2 = userExists(user[1], user[0]);
		if (status2 == Status.NOT_FOUND.getStatusCode()) {
			throw new WebApplicationException(status2); // 404
		}

		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}
		String owner = sheet.getOwner();

		int status = requestUser(domain, owner, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			boolean firstShare = false;
			Set<String> sharedWith = sheet.getSharedWith();
			if (sharedWith == null) {
				sharedWith = new HashSet<String>();
				firstShare = true;
			} else if (sharedWith.contains(userId)) {
				throw new WebApplicationException(Status.CONFLICT); // 409
			}

			sharedWith.add(userId);
			if (firstShare) {
				sheet.setSharedWith(sharedWith);
			}
		}
	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) {

		if (sheetId == null || userId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[] user = userId.split("@");
		int status2 = userExists(user[1], user[0]);
		if (status2 == Status.NOT_FOUND.getStatusCode()) {
			throw new WebApplicationException(status2); // 404
		}

		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}
		String owner = sheet.getOwner();

		int status = requestUser(domain, owner, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		synchronized (this) {
			Set<String> sharedWith = sheet.getSharedWith();
			if (sharedWith != null) {
				sharedWith.remove(userId);
			}
		}
	}

	@Override
	public ValuesResult getRange(int version, String sheetId, String userId, String userDomain, String range, String serverSecret,
			long twClient) {

		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		if (sheetId == null || userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}

		if (!sheet.getOwner().equals(userId) && ((sheet.getSharedWith() == null)
				|| (!sheet.getSharedWith().contains(String.format("%s@%s", userId, userDomain))))) {
			throw new WebApplicationException(Status.FORBIDDEN); // 403
		}

		String[][] values;
		if (twClient < twServer.get(sheetId)) {
			CellRange cellRange = new CellRange(range);
			String[][] rangeValues = cellRange.extractRangeValuesFrom(sheet.getRawValues());

			values = SpreadsheetEngineImpl.getInstance().computeSpreadsheetValues(new AbstractSpreadsheet() {
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
					return importRangeValues(sheetURL, range, sheet.getOwner());
				}
			});
		} else {
			throw new WebApplicationException(Status.NO_CONTENT); // 204
		}
		return new ValuesResult(values, sheet.getSharedWith(), twServer.get(sheetId));
	}

	@Override
	public void deleteUserSpreadsheets(String userId, String serverSecret) {

		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		if (userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}
		synchronized (this) {
			Set<String> sheetIds = userSheets.get(userId);
			if (sheetIds != null) {
				for (String id : sheetIds) {
					sheets.remove(id);
					twServer.remove(id);
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
				if (uri == null) {
					Thread.sleep(500);
				}
			} catch (Exception e) {
			}
		}

		String serverUrl = uri[0].toString();
		short retries = 0;

		if (serverUrl.contains("rest")) {
			WebTarget target = client.target(serverUrl).path(RestUsers.PATH);

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
		} else {
			SoapUsers users = null;
			boolean success = false;

			while (!success && retries < MAX_RETRIES) {
				try {
					QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
					Service service = Service.create(new URL(serverUrl + UsersWS.USERS_WSDL), QNAME);
					users = service.getPort(tp1.api.service.soap.SoapUsers.class);
					success = true;
				} catch (WebServiceException e) {
					retries++;
				} catch (MalformedURLException e) {
				}
			}

			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

			retries = 0;
			success = false;

			while (!success && retries < MAX_RETRIES) {

				try {
					users.getUser(userId, password);
					success = true;
				} catch (WebServiceException wse) {
					retries++;
					try {
						Thread.sleep(RETRY_PERIOD);
					} catch (InterruptedException e) {
					}
				} catch (UsersException ue) {
					throw new WebApplicationException(Status.BAD_REQUEST);
				}
			}
		}

		return 0;
	}

	private int userExists(String userDomain, String userId) {

		URI[] uri = null;
		while (uri == null) {
			try {
				uri = discovery.knownUrisOf(userDomain, "users");
				if (uri == null) {
					Thread.sleep(500);
				}
			} catch (Exception e) {
			}
		}

		String serverUrl = uri[0].toString();
		short retries = 0;

		if (serverUrl.contains("rest")) {
			WebTarget target = client.target(serverUrl).path(RestUsers.PATH);

			while (retries < MAX_RETRIES) {
				try {
					Response r = target.path("exists").path(userId).queryParam("serverSecret", serverSecret).request()
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
		} else {
			SoapUsers users = null;
			boolean success = false;

			while (!success && retries < MAX_RETRIES) {
				try {
					QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
					Service service = Service.create(new URL(serverUrl + UsersWS.USERS_WSDL), QNAME);
					users = service.getPort(tp1.api.service.soap.SoapUsers.class);
					success = true;
				} catch (WebServiceException e) {
					retries++;
				} catch (MalformedURLException e) {
				}
			}

			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT,
					CONNECTION_TIMEOUT);
			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);

			retries = 0;
			success = false;

			while (!success && retries < MAX_RETRIES) {
				try {
					users.userExists(userId, serverSecret);
					success = true;
				} catch (WebServiceException wse) {
					retries++;
					try {
						Thread.sleep(RETRY_PERIOD);
					} catch (InterruptedException e) {
					}
				} catch (UsersException ue) {
					throw new WebApplicationException(Status.BAD_REQUEST);
				}
			}
		}
		return 0;
	}

	private String[][] importRangeValues(String sheetURL, String range, String owner) {
		short retries = 0;
		String sheetRange = String.format("%s@%s", sheetURL, range);
		ValuesResult valuesResult = cache.get(sheetRange);
		long twClient = (valuesResult != null) ? valuesResult.getTwServer() : -1 ;

		if (sheetURL.contains(GOOGLE_SHEETS)) { // no cache
			String sheetID = sheetURL.split(GOOGLE_SHEETS + "/")[1];
			WebTarget target = client.target(GOOGLE_SHEETS).path("v4/spreadsheets");
			while (retries < MAX_RETRIES) {

				try {
					Response r = target.path(sheetID).path("values").path(range).queryParam("key", googleKey).request()
							.accept(MediaType.APPLICATION_JSON).get();

					if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) {
						String[][] values = r.readEntity(GoogleSheetValuesReturn.class).getValues();

						return values;
					} else {
						throw new WebApplicationException(r.getStatus());
					}

				} catch (ProcessingException pe) {
					retries++;
					try {
						Thread.sleep(RETRY_PERIOD);
					} catch (InterruptedException e) {
					}
				}
			}

		} else if (sheetURL.contains("rest")) {
			if (valuesResult == null || System.currentTimeMillis() - tc.get(sheetURL) > CACHE_VALIDITY_TIME) {
				WebTarget target = client.target(sheetURL).path("/range");
				while (retries < MAX_RETRIES) {
					try {
						Response r = target.queryParam("userId", owner).queryParam("userDomain", domain)
								.queryParam("range", range).queryParam("serverSecret", serverSecret)
								.queryParam("twClient", twClient).request()
								.accept(MediaType.APPLICATION_JSON).get();
						
						if (r.getStatus() == Status.NO_CONTENT.getStatusCode()) { // up to date

							tc.put(sheetURL, System.currentTimeMillis());
							if (valuesResult.getSharedWith() != null && valuesResult.getSharedWith().contains(String.format("%s@%s", owner, domain))) {
								return valuesResult.getValues();
							} else {
								throw new WebApplicationException(Status.FORBIDDEN); // 403
							}

						} else if (r.getStatus() == Status.OK.getStatusCode() && r.hasEntity()) { // not updated
							Gson json = new Gson();
							valuesResult = json.fromJson(r.readEntity(String.class), ValuesResult.class);
							cache.put(String.format("%s@%s", sheetURL, range), valuesResult);
							tc.put(sheetURL, System.currentTimeMillis());
							return valuesResult.getValues();

						} else {
							throw new WebApplicationException(r.getStatus());
						}
					} catch (ProcessingException pe) {
						retries++;
						try {
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					}
				}
			}

		} else {

			if (valuesResult == null || System.currentTimeMillis() - tc.get(sheetURL) > CACHE_VALIDITY_TIME) {
				SoapSpreadsheets sheets = null;

				String[] aux = sheetURL.split("/spreadsheets/");
				String serverUrl = aux[0];
				String sheetId = aux[1];

				boolean success = false;

				while (!success && retries < MAX_RETRIES) {
					try {
						QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
						Service service = Service.create(new URL(serverUrl + SpreadsheetsWS.SHEETS_WSDL), QNAME);
						sheets = service.getPort(tp1.api.service.soap.SoapSpreadsheets.class);
						success = true;
					} catch (WebServiceException e) {
						retries++;
					} catch (MalformedURLException e) {
					}
				}

				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT,
						CONNECTION_TIMEOUT);
				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT,
						REPLY_TIMEOUT);

				retries = 0;

				while (retries < MAX_RETRIES) {

					try {
						ValuesResult tempValuesResult = sheets.getRange(sheetId, owner, domain, range, serverSecret, twClient);

						if (twClient < tempValuesResult.getTwServer()) {

							cache.put(String.format("%s@%s", sheetURL, range), tempValuesResult);
							tc.put(sheetURL, System.currentTimeMillis());
							return tempValuesResult.getValues();

						} else {

							tc.put(sheetURL, System.currentTimeMillis());
							if (valuesResult.getSharedWith() != null && valuesResult.getSharedWith().contains(String.format("%s@%s", owner, domain))) {
								return valuesResult.getValues();
							} else {
								throw new WebApplicationException(Status.FORBIDDEN); // 403
							}
						}

					} catch (WebServiceException wse) {
						retries++;
						try {
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					} catch (SheetsException e) {
						throw new WebApplicationException(Status.BAD_REQUEST); // 400
					}
				}
			}

		}

		if (valuesResult != null && valuesResult.getSharedWith().contains(String.format("%s@%s", owner, domain))) {
			return cache.get(sheetRange).getValues();
		} else {
			throw new WebApplicationException(Status.FORBIDDEN); // 403
		}
	}
}