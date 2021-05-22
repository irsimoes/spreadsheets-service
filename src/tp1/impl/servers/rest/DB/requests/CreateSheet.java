package tp1.impl.servers.rest.DB.requests;

import java.io.IOException;

import org.pac4j.scribe.builder.api.DropboxApi20;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth2AccessToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.gson.Gson;

import tp1.api.Spreadsheet;
import tp1.impl.servers.rest.DB.arguments.CreateFileArgs;

public class CreateSheet {

	protected static final String OCTET_STREAM_CONTENT_TYPE = "application/octet-stream";
	private static final String UPLOAD_FILE_URL = "https://content.dropboxapi.com/2/files/upload";
	
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	
	private Gson json;
	
	public CreateSheet(String apiKey, String apiSecret, String accessTokenStr) {
		service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(DropboxApi20.INSTANCE);
		accessToken = new OAuth2AccessToken(accessTokenStr);
		
		json = new Gson();
	}
	
	public boolean execute(Spreadsheet sheet, String path ) {
		OAuthRequest createFile = new OAuthRequest(Verb.POST, UPLOAD_FILE_URL);
		createFile.addHeader("Content-Type", OCTET_STREAM_CONTENT_TYPE);
		createFile.addHeader("Dropbox-API-Arg",json.toJson(new CreateFileArgs(path, "overwrite", false, false, false)));
		
		byte[] sheetBinary = json.toJson(sheet).getBytes();
		createFile.setPayload(sheetBinary);
		
		service.signRequest(accessToken, createFile);
		
		Response r = null;
		
		try {
			r = service.execute(createFile);
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		
		if(r.getCode() == 200) {
			return true;
		} else {
			System.err.println("HTTP Error Code: " + r.getCode() + ": " + r.getMessage());
			try {
				System.err.println(r.getBody());
			} catch (IOException e) {
				System.err.println("No body in the response");
			}
			return false;
		}
		
	}

}
