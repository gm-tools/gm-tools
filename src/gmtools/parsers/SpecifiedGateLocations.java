package gmtools.parsers;


import gmtools.common.Geography;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class SpecifiedGateLocations {
	private static final String HEADER_STAND = "stand";
	private static final String HEADER_LAT = "lat";
	private static final String HEADER_LON = "lon";
	private static final String HEADER_TAXIWAY = "taxiways";
	private static final String HEADER_TERMINAL = "terminal";
	private static final String HEADER_ATTACHMENT = "attachment";
	
	private static final String SEPARATOR = "\t";
	private static final String SEPARATOR_TAXIWAYS = ",";
	
	public static Set<Stand> loadStandsFromFile(String filename) {
		Set<Stand> stands = new TreeSet<Stand>();
		
		try {
			BufferedReader in = new BufferedReader(new FileReader(filename));
			
			String[] header = in.readLine().split(SEPARATOR);
			ColumnIndices columnIndices = new ColumnIndices(header, filename);
			
			String line;
			while ((line = in.readLine()) != null) {
				String[] cols = line.split(SEPARATOR);
				String label = cols[columnIndices.getColumnIndex(HEADER_STAND, true)];
				Integer latIndex = columnIndices.getColumnIndex(HEADER_LAT, false);
				Integer lonIndex = columnIndices.getColumnIndex(HEADER_LON, false);
				Integer locationIndex = columnIndices.getColumnIndex(HEADER_TERMINAL, false);
				Integer taxiwayIndex = columnIndices.getColumnIndex(HEADER_TAXIWAY, false);
				Integer attachmentIndex = columnIndices.getColumnIndex(HEADER_ATTACHMENT, false);
				
				if ((latIndex != null) && (lonIndex != null) && (Math.max(latIndex, lonIndex) < cols.length)) { // at least name, lat, lon
					String location = ((locationIndex != null) && (locationIndex < cols.length)) ? cols[locationIndex] : null;
					double[] dblCoords = Geography.latLonToDecimal(cols[latIndex].trim(), cols[lonIndex].trim());

					// -1 * y coord, because lat is up as values increase, whereas screen is down as values increase
					Stand s = new Stand(label, location, dblCoords[0], dblCoords[1]);
					stands.add(s);
					
					if ((taxiwayIndex != null) && (taxiwayIndex < cols.length)) {
						String[] taxiways = cols[taxiwayIndex].split(SEPARATOR_TAXIWAYS);
						s.associatedTaxiways = taxiways;
					}
					
					if ((attachmentIndex != null) && (attachmentIndex < cols.length)) {
						s.nodeAttachment = cols[attachmentIndex];
					}
				} else { // just use the name
					Stand s = new Stand(label);
					stands.add(s);
				}
			}
			
			in.close();
		} catch (IOException e) {
			System.err.println("Couldn't read data file!");
			e.printStackTrace();
			System.exit(1);
		} catch (NumberFormatException e) {
			System.err.println("Error parsing data file! Probably either non-numberic data, or non-tab separators used");
			e.printStackTrace();
			System.exit(1);
		}
		
		return stands;
	}
	
	/**implements Comparable so ordering can be preserved*/
	public static class Stand implements Comparable<Stand> {
		private String name;
		private String location;
		private double lat;
		private double lon;
		private boolean hasCoords;
		private String[] associatedTaxiways;
		private String nodeAttachment;
		
		public Stand(String name, String location, double lat, double lon) {
			this.name = name;
			this.location = location;
			this.lat = lat;
			this.lon = lon;
			this.hasCoords = true;
			this.associatedTaxiways = new String[0];
			this.nodeAttachment = null;
		}
		
		public Stand(String name) {
			this.name = name;
			this.location = null;
			this.lat = Double.NaN;
			this.lon = Double.NaN;
			this.hasCoords = false;
			this.associatedTaxiways = new String[0];
			this.nodeAttachment = null;
		}
		
		public String getName() {
			return name;
		}
		
		public String getLocation() {
			return location;
		}
		
		public double getLat() {
			return lat;
		}
		
		public double getLon() {
			return lon;
		}
		
		public boolean hasCoords() {
			return hasCoords;
		}
		
		public String[] getAssociatedTaxiways() {
			return associatedTaxiways;
		}
		
		public String getNodeAttachment() {
			return nodeAttachment;
		}
		
		@Override
		public String toString() {
			return name + "-" + location;
		}
		
		@Override
		public int compareTo(Stand that) {
			return this.name.compareTo(that.name);
		}
	}
}
