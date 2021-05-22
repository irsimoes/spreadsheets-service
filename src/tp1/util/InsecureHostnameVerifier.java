package tp1.util;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

public class InsecureHostnameVerifier implements HostnameVerifier {

	@Override
	public boolean verify(String hostname, SSLSession session) {
		//Ignore the verification of host name in the certificate
		//(This should not be used in production systems)
		return true;
	}

}

