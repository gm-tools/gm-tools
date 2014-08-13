package gmtools.parsers;


import gmtools.common.Geography;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/**
 * copyright (c) 2014 Alexander E.I. Brownlee
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class SpecifiedGateLocations {
	public static Set<Stand> loadStandsFromFile(String filename) {
		Set<Stand> stands = new TreeSet<Stand>();
		
		try {
			BufferedReader in = new BufferedReader(new FileReader(filename));
			in.readLine(); // skip header
			String line;
			while ((line = in.readLine()) != null) {
				String[] cols = line.split("\t");
				
				if (cols.length >= 3) {
					String label = cols[0];
					String location = cols[2];
					String[] strCoords = cols[1].split(" ");
					double[] dblCoords = Geography.latLonToDecimal(strCoords[0].trim(), strCoords[1].trim());

					// -1 * y coord, because lat is up as values increase, whereas screen is down as values increase
					Stand s = new Stand(label, location, dblCoords[0], dblCoords[1]);
					stands.add(s);
					
					if (cols.length >= 4) {
						String[] taxiways = cols[3].split(",");
						s.associatedTaxiways = taxiways;
						
						if (cols.length >= 5) { // specific node to attach to identified
							s.nodeAttachment = cols[4];
						}
					}
				}
			}
			
			in.close();
		} catch (IOException e) {
			System.err.println("Couldn't read data file!");
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
		
		public Stand(String name, String location) {
			this.name = name;
			this.location = location;
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
