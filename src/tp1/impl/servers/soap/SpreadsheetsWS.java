package tp1.impl.servers.soap;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.namespace.QName;

import com.sun.xml.ws.client.BindingProviderProperties;

import jakarta.inject.Singleton;
import jakarta.jws.WebService;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.WebServiceException;
import tp1.api.Spreadsheet;
import tp1.api.engine.AbstractSpreadsheet;
import tp1.api.service.soap.SheetsException;
import tp1.api.service.soap.SoapSpreadsheets;
import tp1.api.service.soap.SoapUsers;
import tp1.api.service.soap.UsersException;
import tp1.discovery.Discovery;
import tp1.impl.engine.SpreadsheetEngineImpl;
import tp1.util.CellRange;

@WebService(serviceName = SoapSpreadsheets.NAME,
targetNamespace = SoapSpreadsheets.NAMESPACE,
endpointInterface = SoapSpreadsheets.INTERFACE)

@Singleton
public class SpreadsheetsWS implements SoapSpreadsheets {
	
public final static String SHEETS_WSDL = "/spreadsheets/?wsdl";
	
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

	public SpreadsheetsWS() {
	}

	public SpreadsheetsWS(Discovery discovery, String domain) {
		SpreadsheetsWS.discovery = discovery;
		SpreadsheetsWS.domain = domain;
	}
	
	@Override
	public String createSpreadsheet(Spreadsheet sheet, String password) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheet == null || password == null || sheet.getSheetId() != null || sheet.getSheetURL() != null
				|| sheet.getRows() < 0 || sheet.getColumns() < 0) {
			throw new SheetsException(); 
		}

		requestUser(domain, sheet.getOwner(), password);

		try {
			String id = UUID.randomUUID().toString();
			sheet.setSheetId(id);

			String ip = InetAddress.getLocalHost().getHostAddress();
			String sheetURL = String.format("http://%s:%s/soap/spreadsheets/%s", ip, PORT, id);
			sheet.setSheetURL(sheetURL);

			synchronized (this) {
				sheets.put(id, sheet);

				Set<String> sheetsSet = userSheets.get(sheet.getOwner());
				if (sheetsSet == null) {
					sheetsSet = new HashSet<String>();
				}

				sheetsSet.add(id);
				userSheets.put(sheet.getOwner(), sheetsSet);
			}
		} catch (UnknownHostException e) {
		}

		return sheet.getSheetId();
	}

	@Override
	public void deleteSpreadsheet(String sheetId, String password) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || password == null) {
			throw new SheetsException(); 
		}

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}
			owner = sheet.getOwner();
		}

		requestUser(domain, owner, password);

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}
			if (sheet.getOwner().equals(owner)) {
				sheets.remove(sheetId);
			}
		}
	}

	@Override
	public Spreadsheet getSpreadsheet(String sheetId, String userId, String password) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null) {
			throw new SheetsException(); 
		}

		requestUser(domain, userId, password);
		
		Spreadsheet sheet;
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException();
			}

			if(!sheet.getOwner().equals(userId)  && ((sheet.getSharedWith() == null) || (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))){
				throw new SheetsException(); 
			}
		}
		return sheet;
	}

	@Override
	public void shareSpreadsheet(String sheetId, String userId, String password) throws SheetsException {
		// TODO Auto-generated method stub
		
		if (sheetId == null || userId == null || password == null) {
			throw new SheetsException();
		}

		String[] user = userId.split("@");
		userExists(user[1], user[0]);

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException();
			}
			owner = sheet.getOwner();
		}

		requestUser(domain, owner, password);

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}
			Set<String> sharedWith = sheet.getSharedWith();
			if (sharedWith == null) {
				sharedWith = new HashSet<String>();
			} else if(sharedWith.contains(userId)) {
				throw new SheetsException(); 
			}
			
			sharedWith.add(userId);
			sheet.setSharedWith(sharedWith);

		}
	}

	@Override
	public void unshareSpreadsheet(String sheetId, String userId, String password) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null || password == null) {
			throw new SheetsException(); 
		}

		String[] user = userId.split("@");
		userExists(user[1], user[0]);

		String owner;
		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}
			owner = sheet.getOwner();
		}

		requestUser(domain, owner, password);


		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}
			Set<String> sharedWith = sheet.getSharedWith();
			if(sharedWith != null) {
				sharedWith.remove(userId);
			}
		}
	}

	@Override
	public void updateCell(String sheetId, String cell, String rawValue, String userId, String password)
			throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null || password == null || rawValue == null) {
			throw new SheetsException();
		}

		requestUser(domain, userId, password);

		synchronized (this) {
			Spreadsheet sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException();
			}

			if(!sheet.getOwner().equals(userId)  && ((sheet.getSharedWith() == null) || (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))){
				throw new SheetsException(); 
			}

			sheet.setCellRawValue(cell, rawValue);
		}
	}

	@Override
	public String[][] getSpreadsheetValues(String sheetId, String userId, String password) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null) {
			throw new SheetsException(); 
		}

		requestUser(domain, userId, password);

		String[][] values;
		Spreadsheet sheet;
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}

			if(!sheet.getOwner().equals(userId)  && ((sheet.getSharedWith() == null) || (!sheet.getSharedWith().contains(String.format("%s@%s", userId, domain))))){
				throw new SheetsException(); 
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
				SoapSpreadsheets sheets = null;
				
				String[] aux = sheetURL.split("/spreadsheets/");
				String serverUrl = aux[0];
				String sheetId = aux[1];
				
				short retries = 0;
				boolean success = false;
				
				while(!success && retries < MAX_RETRIES) {
					try {
						QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
						Service service = Service.create( new URL(serverUrl + SHEETS_WSDL), QNAME);
						sheets = service.getPort( tp1.api.service.soap.SoapSpreadsheets.class);
						success = true;
					} catch (WebServiceException e) {
						retries++;
					} catch (MalformedURLException e) {
					}
				}
				
				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);
				
				retries = 0;
				
				String[][] values;
				while(retries < MAX_RETRIES) {
					
					try{
						values = sheets.getRange(sheetId, sheet.getOwner(), domain, range); //MUDAR
						cellCache.put(String.format("%s@%s", sheetURL, range), values);
						return values;
					} catch(WebServiceException wse) {
						retries++;
						try { 
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					} catch (SheetsException e) {
						values = cellCache.get(String.format("%s@%s", sheetURL, range));
						return values;
					}
				}
				return cellCache.get(String.format("%s@%s", sheetURL, range));
			}
		});
		return values; 
	}

	@Override
	public String[][] getRange(String sheetId, String userId, String userDomain, String range) throws SheetsException {
		// TODO Auto-generated method stub
		if (sheetId == null || userId == null) {
			throw new SheetsException(); 
		}

		String[][] rangeValues;
		Spreadsheet sheet;
		String user = String.format("%s@%s", userId, userDomain);
		
		synchronized (this) {
			sheet = sheets.get(sheetId);
			if (sheet == null) {
				throw new SheetsException(); 
			}

			if(!sheet.getOwner().equals(userId)  && ((sheet.getSharedWith() == null) || (!sheet.getSharedWith().contains(user)))){
				throw new SheetsException(); 
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

				SoapSpreadsheets sheets = null;
				
				String[] aux = sheetURL.split("/spreadsheets/");
				String serverUrl = aux[0];
				String sheetId = aux[1];
				
				short retries = 0;
				boolean success = false;
				
				while(!success && retries < MAX_RETRIES) {
					try {
						QName QNAME = new QName(SoapSpreadsheets.NAMESPACE, SoapSpreadsheets.NAME);
						Service service = Service.create( new URL(serverUrl + SHEETS_WSDL), QNAME);
						sheets = service.getPort( tp1.api.service.soap.SoapSpreadsheets.class);
						success = true;
					} catch (WebServiceException e) {
						retries++;
					} catch (MalformedURLException e) {
					}
				}
				
				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
				((BindingProvider) sheets).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);
				
				retries = 0; success = false;
				
				String[][] values;
				while(!success && retries < MAX_RETRIES) {
					
					try{
						values = sheets.getRange(sheetId, userId, domain, range);
						success = true;
						cellCache.put(String.format("%s@%s", sheetURL, range), values);
					} catch(WebServiceException wse) {
						retries++;
						try { 
							Thread.sleep(RETRY_PERIOD);
						} catch (InterruptedException e) {
						}
					} catch (SheetsException e) {
						values = cellCache.get(String.format("%s@%s", sheetURL, range));
						if(values != null) {
							return values;
						}
					}
				}
				return cellCache.get(String.format("%s@%s", sheetURL, range));
			}
		});
		return values; 
	}

	@Override
	public void deleteUserSpreadsheets(String userId) throws SheetsException {
		// TODO Auto-generated method stub
		if (userId == null) {
			throw new SheetsException(); 
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
	
	private void requestUser(String userDomain, String userId, String password) throws SheetsException {
		URI[] uri = null;
		while(uri == null) {
			try {
				uri = discovery.knownUrisOf(domain, "users");
				Thread.sleep(500);
			} catch (Exception e) {
			}
		}
		
		String serverUrl = uri[0].toString();
		SoapUsers users = null;
		
		short retries = 0;
		boolean success = false;
		
		while(!success && retries < MAX_RETRIES) {
			try {
				QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
				Service service = Service.create( new URL(serverUrl + UsersWS.USERS_WSDL), QNAME);
				users = service.getPort( tp1.api.service.soap.SoapUsers.class);
				success = true;
			} catch (WebServiceException e) {
				retries++;
			} catch (MalformedURLException e) {
			}
		}
		
		((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		((BindingProvider) users).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);
		
		retries = 0; success = false;
		
		while(!success && retries < MAX_RETRIES) {
			
			try{
				users.getUser(userId, password);
				success = true;
			} catch(WebServiceException wse) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException e) {
				}
			} catch(UsersException ue) {
				throw new SheetsException();
			}
		}
	}
	
	private void userExists(String userDomain, String userId) throws SheetsException {
		URI[] uri = null;
		while(uri == null) {
			try {
				uri = discovery.knownUrisOf(userDomain, "users");
				Thread.sleep(500);
			} catch (Exception e) {
			}
		}
		
		String serverUrl = uri[0].toString();
		SoapUsers users = null;
		
		short retries = 0;
		boolean success = false;
		
		while(!success && retries < MAX_RETRIES) {
			try {
				QName QNAME = new QName(SoapUsers.NAMESPACE, SoapUsers.NAME);
				Service service = Service.create( new URL(serverUrl + UsersWS.USERS_WSDL), QNAME);
				users = service.getPort( tp1.api.service.soap.SoapUsers.class);
				success = true;
			} catch (WebServiceException e) {
				retries++;
			} catch (MalformedURLException e) {
			}
		}
		
		((BindingProvider) users).getRequestContext().put(BindingProviderProperties.CONNECT_TIMEOUT, CONNECTION_TIMEOUT);
		((BindingProvider) users).getRequestContext().put(BindingProviderProperties.REQUEST_TIMEOUT, REPLY_TIMEOUT);
		
		retries = 0; success = false;
		
		while(!success && retries < MAX_RETRIES) {
			try{
				users.userExists(userId);
				success = true;
			} catch(WebServiceException wse) {
				retries++;
				try {
					Thread.sleep(RETRY_PERIOD);
				} catch (InterruptedException e) {
				}
			} catch(UsersException ue) {
				throw new SheetsException();
			}
		}
	}

}