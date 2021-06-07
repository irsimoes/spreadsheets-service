package tp1.impl.servers.rest.replication;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.namespace.QName;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;

import com.google.gson.Gson;
import com.sun.xml.ws.client.BindingProviderProperties;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Response.Status;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.api.Spreadsheet;
import tp1.api.ValuesResult;
import tp1.api.engine.AbstractSpreadsheet;
import tp1.api.replies.GoogleSheetValuesReturn;
import tp1.api.service.rest.RestRepSpreadsheets;
import tp1.api.service.rest.RestSpreadsheets;
import tp1.api.service.rest.RestUsers;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.discovery.Discovery;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.impl.servers.soap.SpreadsheetsWS;
import tp1.impl.servers.soap.UsersWS;
import tp1.util.CellRange;
import tp1.zookeeper.ZookeeperProcessor;

public class SpreadsheetsRepResource implements RestRepSpreadsheets {
	private static final int PORT = 8080;
	private static final int MAX_RETRIES = 3;
	private static final long RETRY_PERIOD = 1000;
	private static final int CONNECTION_TIMEOUT = 10000;
	private static final int REPLY_TIMEOUT = 600;
	private static final int CACHE_VALIDITY_TIME = 20000;
	private static final String GOOGLE_SHEETS = "https://sheets.googleapis.com";

	private static final String CREATE = "create";
	private static final String DELETE = "delete";
	private static final String UPDATE = "update";
	private static final String SHARE = "share";
	private static final String UNSHARE = "unshare";
	private static final String DELETE_USER = "deleteUser";

	private Map<String, Spreadsheet> sheets = new HashMap<String, Spreadsheet>();
	private Map<String, Set<String>> userSheets = new HashMap<String, Set<String>>();
	private Map<String, ValuesResult> cache = new HashMap<String, ValuesResult>();
	private Map<String, Long> twServer = new HashMap<String, Long>();
	private Map<String, Long> tc = new HashMap<String, Long>();
	private Discovery discovery;
	private ZookeeperProcessor zk;
	private String domain, serverSecret, googleKey;
	private Client client;
	private boolean isPrimary;
	private String primaryURI, serverURI, path;
	private ReplicationManager repManager;
	private Gson json;

	public SpreadsheetsRepResource(String domain, String serverURI, String serverSecret, String googleKey,
			Discovery discovery, ZookeeperProcessor zk, ReplicationManager repManager) {
		this.discovery = discovery;
		this.zk = zk;
		this.domain = domain;
		this.serverURI = serverURI;
		this.serverSecret = serverSecret;
		this.googleKey = googleKey;
		this.repManager = repManager;
		json = new Gson();

		ClientConfig config = new ClientConfig();
		config.property(ClientProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		config.property(ClientProperties.READ_TIMEOUT, REPLY_TIMEOUT);
		client = ClientBuilder.newClient(config);
		path = String.format("/%s", domain);

		isPrimary = false;
		monitorDomain();
		if(!isPrimary) {
			getCurrentState();
		}
	}

	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) {

		if (!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(
					UriBuilder.fromUri(primaryURI).path(RestSpreadsheets.PATH).queryParam("password", password).build())
					.build());
		}

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

		} catch (UnknownHostException e) {
		}

		// System.out.println("entrei no if");
		String sheetJson = json.toJson(sheet);
		String[] params = new String[] { sheetJson, password };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, CREATE, params);
		// System.out.println("pedido versao :" + (repManager.getCurrentVersion() + 1) + " sheetId: " + sheet.getSheetId());
		handleRequest(operation);
		synchronized (this) {
			repManager.addOperation(operation);
			return create(sheet, password);
		}
	}

	private String create(Spreadsheet sheet, String password) {
		synchronized (this) {
			String id = sheet.getSheetId();
			sheets.put(id, sheet);

			Set<String> sheetsSet = userSheets.get(sheet.getOwner());
			if (sheetsSet == null) {
				sheetsSet = new HashSet<String>();
			}

			sheetsSet.add(id);
			userSheets.put(sheet.getOwner(), sheetsSet);
			twServer.put(id, System.currentTimeMillis());
		}

		//System.out.println(sheet.getSheetId() + "->" + json.toJson(sheet));
		return sheet.getSheetId();
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) {
		if (sheetId == null || password == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		if(!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(UriBuilder.fromUri(primaryURI)
					.path(String.format("%s/%s", RestSpreadsheets.PATH, sheetId)).queryParam("password", password).build()).build());
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

		String[] params = new String[] { sheetId, password };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, DELETE, params);
		handleRequest(operation);
		delete(sheetId, password);
		repManager.addOperation(operation);
	}

	private void delete(String sheetId, String password) {

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new WebApplicationException(Status.NOT_FOUND); // 404
			}
			sheets.remove(sheetId);
			userSheets.get(sheet.getOwner()).remove(sheetId);
			twServer.remove(sheetId);
		}
	}

	@Override
	public Spreadsheet getSpreadsheet(int version, String sheetId, String userId, String password) {
		if (!isPrimary) {
			if (version > repManager.getCurrentVersion()) {
				
				boolean success = false;
				int retries = 0;

				while (!success && retries < MAX_RETRIES) {
					success = askForUpdate(primaryURI, version);
				}
				

				if (!success) {
					throw new WebApplicationException(Response
							.temporaryRedirect(
									UriBuilder.fromUri(primaryURI).path(String.format("%s/%s",RestSpreadsheets.PATH ,sheetId))
											.queryParam("userId", userId).queryParam("password", password).build())
							.header(RestSpreadsheets.HEADER_VERSION, version).build());
				}
			}
		}

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
		System.out.println("recebeu pedido " + sheetId);
		if (!isPrimary) {
			System.out.println("comparacao versoes " + version + " " + repManager.getCurrentVersion());
			if (version > repManager.getCurrentVersion()) {
				System.out.println("pediu update " + sheetId);
				boolean success = false;
				int retries = 0;

				while (!success && retries < MAX_RETRIES) {
					success = askForUpdate(primaryURI, version);
				}
				System.out.println("pedido update acabou " + sheetId + " " + repManager.getCurrentVersion());

				if (!success) {
					System.out.println("nao teve sucesso " + sheetId);
					throw new WebApplicationException(Response
							.temporaryRedirect(UriBuilder.fromUri(primaryURI)
									.path(String.format("%s/%s/values",RestSpreadsheets.PATH ,sheetId)).queryParam("userId", userId)
									.queryParam("password", password).build())
							.header(RestSpreadsheets.HEADER_VERSION, version).build());
				}
			}
		}

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

		if(!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(
					UriBuilder.fromUri(primaryURI).path(String.format("%s/%s/%s",RestSpreadsheets.PATH ,sheetId, cell))
							.queryParam("userId", userId).queryParam("password", password).build())
					.build());
		}

		if (sheetId == null || userId == null || password == null || rawValue == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		int status = requestUser(domain, userId, password);
		if (status != Status.OK.getStatusCode()) {
			throw new WebApplicationException(status); // 403 or 404
		}

		String[] params = new String[] { sheetId, cell, rawValue, userId, password };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, UPDATE, params);
		handleRequest(operation);
		update(sheetId, cell, rawValue, userId, password);
		repManager.addOperation(operation);
	}

	private void update(String sheetId, String cell, String rawValue, String userId, String password) {
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

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) {

		if(!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(
				UriBuilder.fromUri(primaryURI).path(String.format("%s/%s/share/%s",RestSpreadsheets.PATH ,sheetId, userId))
						.queryParam("password", password).build())
				.build());
		}

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

		String[] params = new String[] { sheetId, userId, password };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, SHARE, params);
		handleRequest(operation);
		share(sheetId, userId, password);
		repManager.addOperation(operation);

	}

	private void share(String sheetId, String userId, String password) {
		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
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

		if(!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(
					UriBuilder.fromUri(primaryURI).path(String.format("%s/%s/share/%s",RestSpreadsheets.PATH ,sheetId, userId))
							.queryParam("password", password).build())
					.build());
		}
		
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

		String[] params = new String[] { sheetId, userId, password };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, UNSHARE, params);
		handleRequest(operation);
		unshare(sheetId, userId, password);
		repManager.addOperation(operation);

	}

	private void unshare(String sheetId, String userId, String password) {
		Spreadsheet sheet = sheets.get(sheetId);
		if (sheet == null) {
			throw new WebApplicationException(Status.NOT_FOUND); // 404
		}

		synchronized (this) {
			Set<String> sharedWith = sheet.getSharedWith();
			if (sharedWith != null) {
				sharedWith.remove(userId);
			}
		}
	}

	@Override
	public ValuesResult getRange(int version, String sheetId, String userId, String userDomain, String range,
			String serverSecret, long twClient) {
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		if (!isPrimary) {
			if (version > repManager.getCurrentVersion()) {
				boolean success = false;
				int retries = 0;

				while (!success && retries < MAX_RETRIES) {
					success = askForUpdate(primaryURI, version);
				}

				if (!success) {
					// System.err.println("n deu para dar update");
					throw new WebApplicationException(Response
							.temporaryRedirect(UriBuilder.fromUri(primaryURI)
									.path(String.format("%s/%s/range",RestSpreadsheets.PATH ,sheetId)).queryParam("userId", userId)
									.queryParam("userDomain", userDomain).queryParam("range", range)
									.queryParam("serverSecret", serverSecret).queryParam("twClient", twClient).build())
							.header(RestSpreadsheets.HEADER_VERSION, version).build());
				}
			}
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

		if(!isPrimary) {
			throw new WebApplicationException(Response.temporaryRedirect(
				UriBuilder.fromUri(primaryURI).path(String.format("%s/delete/%s",RestSpreadsheets.PATH ,userId))
						.queryParam("serverSecret", serverSecret).build())
				.build());
		}
		
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		if (userId == null) {
			throw new WebApplicationException(Status.BAD_REQUEST); // 400
		}

		String[] params = new String[] { userId };
		Operation operation = new Operation(repManager.getCurrentVersion() + 1, DELETE_USER, params);
		handleRequest(operation);
		deleteUser(userId);
		repManager.addOperation(operation);
	}

	public void deleteUser(String userId) {
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

			((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT,
					CONNECTION_TIMEOUT);
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
		long twClient = (valuesResult != null) ? valuesResult.getTwServer() : -1;

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
								.queryParam("twClient", twClient).request().accept(MediaType.APPLICATION_JSON).get();

						if (r.getStatus() == Status.NO_CONTENT.getStatusCode()) { // up to date

							tc.put(sheetURL, System.currentTimeMillis());
							if (valuesResult.getSharedWith() != null
									&& valuesResult.getSharedWith().contains(String.format("%s@%s", owner, domain))) {
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
						ValuesResult tempValuesResult = sheets.getRange(sheetId, owner, domain, range, serverSecret,
								twClient);

						if (twClient < tempValuesResult.getTwServer()) {

							cache.put(String.format("%s@%s", sheetURL, range), tempValuesResult);
							tc.put(sheetURL, System.currentTimeMillis());
							return tempValuesResult.getValues();

						} else {

							tc.put(sheetURL, System.currentTimeMillis());
							if (valuesResult.getSharedWith() != null
									&& valuesResult.getSharedWith().contains(String.format("%s@%s", owner, domain))) {
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

	private void monitorDomain() {
		List<String> lst = zk.getChildren(path);
		findPrimary(lst);

		zk.getChildren(path, new Watcher() {
			@Override
			public void process(WatchedEvent event) {
				List<String> lst = zk.getChildren(path, this);
				findPrimary(lst);
				getMostRecentVersion(lst);
			}
		});

	}

	private void findPrimary(List<String> lst) {
		int minSeq = Integer.MAX_VALUE;
		lst.stream().forEach(e -> {
			int seqNum = Integer.parseInt(e.split("_")[1]);
			if (seqNum < minSeq) {
				primaryURI = zk.getValue(String.format("/%s/%s", domain, e));
				// System.out.println(primaryURI);
				if (primaryURI.equals(serverURI)) {
					isPrimary = true;
				} else {
					isPrimary = false;
				}
			}
		});
		// System.out.println(primaryURI);
		// System.out.println(isPrimary);
	}

	private void handleRequest(Operation operation) {
		List<String> lst = zk.getChildren(path);
		AckCheck sendToSecondaries = new AckCheck();
		List<Thread> threads = new ArrayList<Thread>(lst.size());
		lst.stream().forEach(e -> {
			Thread thread = new Thread(() -> {
				String replicaURI = zk.getValue(String.format("/%s/%s", domain, e));
				if (!replicaURI.equals(serverURI)) {
					int retries = 0;
					boolean success = false;
					WebTarget target = client.target(replicaURI).path(RestSpreadsheets.PATH).path("execute");

					while (!success && retries < MAX_RETRIES) {
						try {
							Response r = target.queryParam("serverSecret", serverSecret).request()
									.post(Entity.entity(operation, MediaType.APPLICATION_JSON));

							if (r.getStatus() == Status.NO_CONTENT.getStatusCode()) {
								// System.out.println("executou");
								// System.out.println(e);
								sendToSecondaries.acksInc();
							}
							// System.out.println(r.getStatus());
							success = true;
							// System.out.println("nao executou");
						} catch (ProcessingException pe) {
							retries++;
							try {
								Thread.sleep(RETRY_PERIOD);
							} catch (InterruptedException ie) {
							}
						}
					}
				}
			});
			threads.add(thread);
			thread.start();
		});

		while (!sendToSecondaries.hasSucceded()) {
		boolean isRunning = false;
		for (Thread thread : threads)
		if (thread.isAlive())
		isRunning = true;
		if (!isRunning)
		break;
		try {
		Thread.sleep(250);
		} catch (Exception e) {
		}
		}
		if (!sendToSecondaries.hasSucceded())
		throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR);

	}

	private synchronized boolean askForUpdate(String URI, int lastVersion) {// este last version e a primeira versao que
																			// nao e atualizada
		int retries = 0;
		int firstOp = repManager.getCurrentVersion() + 1;
		if (firstOp >= lastVersion) {
			return true;
		}
		int nOperations = (lastVersion - firstOp) + 1; // faz as ops antigas que faltavam inclusive a lastversion

		while (retries < MAX_RETRIES) {
			WebTarget target = client.target(URI).path(RestSpreadsheets.PATH).path("operations");
			try {
				Response r = target.queryParam("firstOp", firstOp).queryParam("nOperations", nOperations)
						.queryParam("serverSecret", serverSecret).request().accept(MediaType.APPLICATION_JSON).get();

				if (r.getStatus() == Status.OK.getStatusCode()) {
					Operation[] operations = r.readEntity(Operation[].class);
					for (Operation op : operations) {
						operationBreaker(op);
					}

					return true;
				}

			} catch (ProcessingException pe) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException ie) {
				}
			}
		}
		return false;
	}

	@Override
	public synchronized void executeOperation(Operation operation, String serverSecret) {
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		if (operation.getVersion() > repManager.getCurrentVersion() + 1) {
			boolean success = false;
			int retries = 0;

			while (!success && retries < MAX_RETRIES) {
				success = askForUpdate(primaryURI, operation.getVersion()-1);
			}
		}

		if (operation.getVersion() == repManager.getCurrentVersion() + 1) {
			operationBreaker(operation);
		} else {
			// System.out.println(operation.getVersion());
			// System.out.println(repManager.getCurrentVersion() + 1);
		}

	}

	private void getCurrentState() {
		int retries = 0;
		boolean success = false;

		while (!success && retries < MAX_RETRIES) {
			WebTarget target = client.target(primaryURI).path(RestSpreadsheets.PATH).path("state");
			try {
				Response r = target.queryParam("serverSecret", serverSecret).request()
						.accept(MediaType.APPLICATION_JSON).get();

				if (r.getStatus() == Status.OK.getStatusCode()) {
					Operation[] operations = r.readEntity(Operation[].class); // sera q e preciso usar gson?
					for (Operation op : operations) {
						operationBreaker(op);
					}
				}

				success = true;

			} catch (ProcessingException pe) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException ie) {
				}
			}
		}
	}

	private void getMostRecentVersion(List<String> lst) {

		MostRecentVersion mostRecentVersion = new MostRecentVersion();
		List<Thread> threads = new ArrayList<Thread>(lst.size());
		lst.stream().forEach(e -> {
			Thread thread = new Thread(() -> {
				String currURI = zk.getValue(String.format("/%s/%s", domain, e));
				int version = getReplicaCurrVersion(currURI);
				mostRecentVersion.setHigherVersion(version, currURI);
			});
			threads.add(thread);
			thread.start();
		});

		for(;;) {
			boolean isRunning = false;
			for (Thread thread : threads)
				if (thread.isAlive())
					isRunning = true;
			if (!isRunning)
				break;
			try {
				Thread.sleep(250);
			} catch (Exception e) {
			}
		}

		if (!(mostRecentVersion.getUrl().equals(serverURI))) {
			askForUpdate(mostRecentVersion.getUrl(), mostRecentVersion.getVersion());
		}
		
	}

	private int getReplicaCurrVersion(String replicaURI) {
		int retries = 0;

		while (retries < MAX_RETRIES) {
			WebTarget target = client.target(replicaURI).path(RestSpreadsheets.PATH).path("version");
			try {
				Response r = target.queryParam("serverSecret", serverSecret).request()
						.accept(MediaType.APPLICATION_JSON).get();

				if (r.getStatus() == Status.OK.getStatusCode()) {
					int version = r.readEntity(Integer.class);
					return version;
				}
			} catch (ProcessingException pe) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException ie) {
				}
			}
		}
		return -1;
	}

	private void operationBreaker(Operation operation) {
		String[] params = operation.getParams();
		switch (operation.getOperation()) {
			case CREATE:
				Spreadsheet sheet = json.fromJson(params[0], Spreadsheet.class);
				// System.out.println(sheet);
				create(sheet, params[1]);
				break;
			case DELETE:
				delete(params[0], params[1]);
				break;
			case UPDATE:
				update(params[0], params[1], params[2], params[3], params[4]);
				break;
			case SHARE:
				share(params[0], params[1], params[2]);
				break;
			case UNSHARE:
				unshare(params[0], params[1], params[2]);
				break;
			case DELETE_USER:
				deleteUser(params[0]);
				break;
			default:
		}
		repManager.addOperation(operation);
		// System.out.println("what");
	}

	@Override
	public Operation[] getDataBase(String serverSecret) {
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}
		return repManager.getAll();
	}

	@Override
	public Operation[] getOperations(int firstOp, int nOperations, String serverSecret) {
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		return repManager.getOperations(firstOp, nOperations);
	}

	@Override
	public int getVersion(String serverSecret) {
		if (!serverSecret.equals(this.serverSecret)) {
			throw new WebApplicationException(Status.FORBIDDEN);
		}

		return repManager.getCurrentVersion();
	}
}