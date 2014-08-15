package gmtools.parsers;

import java.util.Map;
import java.util.TreeMap;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 * <br/><br/>
 * wraps up headings on a delimited text file (ignores case)
 */
public class ColumnIndices {
	Map<String,Integer> columnIndices;
	private String filenameForError;
	
	public ColumnIndices(String[] header, String filenameForError) {
		this.filenameForError = filenameForError;
		this.columnIndices = new TreeMap<String,Integer>(); // treemap for deterministic behaviour
		for (int i = 0; i < header.length; i++) {
			this.columnIndices.put(header[i].toLowerCase(), i);
		}
	}
	
	public Integer getColumnIndex(String colName, boolean failIfNotFound) {
		colName = colName.toLowerCase();
		Integer rval = this.columnIndices.get(colName);
		
		if ((rval == null) && (failIfNotFound)) {
			System.err.println("Couldn't find header " + colName + " in " + this.filenameForError);
			System.exit(1);
		}
		
		return rval;
	}
}
