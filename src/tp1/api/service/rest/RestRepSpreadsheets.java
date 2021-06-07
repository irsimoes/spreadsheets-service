package tp1.api.service.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import tp1.impl.servers.rest.replication.Operation;

@Path(RestSpreadsheets.PATH)
public interface RestRepSpreadsheets extends RestSpreadsheets {

	@POST
	@Path("/execute")
	@Consumes(MediaType.APPLICATION_JSON)
	void executeOperation(Operation operation, @QueryParam("serverSecret") String serverSecret);
	
	@GET
	@Path("/state")
	@Produces(MediaType.APPLICATION_JSON)
	Operation[] getDataBase(@QueryParam("serverSecret") String serverSecret);
	
	@GET
	@Path("/operations")
	@Produces(MediaType.APPLICATION_JSON)
	Operation[] getOperations(@QueryParam("firstOp") int firstOp, @QueryParam("nOperations") int nOperations, @QueryParam("serverSecret") String serverSecret);

	@GET
	@Path("/version")
	@Produces(MediaType.APPLICATION_JSON)
	int getVersion(@QueryParam("serverSecret") String serverSecret);
}
