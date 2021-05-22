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
import tp1.impl.servers.rest.DB.arguments.PathArgs;

public class GetSheet {

	protected static final String OCTET_STREAM_CONTENT_TYPE = "application/octet-stream";
	private static final String DOWNLOAD_URL = "https://content.dropboxapi.com/2/files/download";
	
	private OAuth20Service service;
	private OAuth2AccessToken accessToken;
	
	private Gson json;
	
	public GetSheet(String apiKey, String apiSecret, String accessTokenStr) {
		service = new ServiceBuilder(apiKey).apiSecret(apiSecret).build(DropboxApi20.INSTANCE);
		accessToken = new OAuth2AccessToken(accessTokenStr);
		
		json = new Gson();
	}
	
	public Spreadsheet execute( String path ) {
		OAuthRequest getSheet = new OAuthRequest(Verb.POST, DOWNLOAD_URL);
		getSheet.addHeader("Content-Type", OCTET_STREAM_CONTENT_TYPE);
		getSheet.addHeader("Dropbox-API-Arg", json.toJson(new PathArgs(path)));

		service.signRequest(accessToken, getSheet);
		
		Response r = null;
		
		try {
			r = service.execute(getSheet);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		
		if(r.getCode() == 200) {
			try {
				return json.fromJson(r.getBody(), Spreadsheet.class);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			System.err.println("HTTP Error Code: " + r.getCode() + ": " + r.getMessage());
			try {
				System.err.println(r.getBody());
			} catch (IOException e) {
				System.err.println("No body in the response");
			}
		}
		return null;
	}

}