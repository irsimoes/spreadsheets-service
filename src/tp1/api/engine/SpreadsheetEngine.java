package tp1.api.engine;

import java.util.List;

/**
 * 
 * The SpreadsheeEngine class is used to compute the values of a spreadsheet from its raw values.
 * 
 * 
 * @author smd
 *
 */
public interface SpreadsheetEngine {

	/**
	 * 
	 * @param sheet - The spreadsheet whose cells will be used to compute the values
	 * @return the full "matrix" of cell values.
	 */
	public List<List<String>> computeSpreadsheetValues( AbstractSpreadsheet sheet );

}
