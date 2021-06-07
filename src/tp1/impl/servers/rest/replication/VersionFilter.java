package tp1.impl.servers.rest.replication;

import java.io.IOException;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import tp1.api.service.rest.RestSpreadsheets;

@Provider
public class VersionFilter implements ContainerResponseFilter {
    ReplicationManager repManager;

    VersionFilter( ReplicationManager repManager) {
        this.repManager = repManager;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) 
                throws IOException {
    	response.getHeaders().add(RestSpreadsheets.HEADER_VERSION, repManager.getCurrentVersion());
    }
}