package gmtools.snaptracks;

import uk.me.jstott.jcoord.LatLng;
import uk.me.jstott.jcoord.UTMRef;

/**
 * copyright (c) 2014-2015 Alexander E.I. Brownlee (sbr@cs.stir.ac.uk)
 * Released under the MIT Licence http://opensource.org/licenses/MIT
 * Instructions, citation information, licencing and source
 * are available at https://github.com/gm-tools/gm-tools/
 */
public class DisplaceAroundAPoint {
	/**original point is first item in returned array*/
	public static LatLng[] coordsAroundAPoint(LatLng origin, int maxStepsOut, double stepWidthMetres) {
		int centreX = 0;
		int centreY = 0;
		
		LatLng[] coords = new LatLng[(maxStepsOut * 2 + 1) * (maxStepsOut * 2 + 1)];
		int pointer = 0;
		coords[pointer++] = origin;
		UTMRef utmStart = origin.toUTMRef();
		
		for (int currentStep = 1; currentStep <= maxStepsOut; currentStep++) {
			for (int currentSide = 0; currentSide < 4; currentSide++) { // 0 top, 1 right, 2 bottom, 3 left
				for (int point = (0 - currentStep) + 1; point <= currentStep; point++) { // start halfway back from middle of this side, plus 1 to leave room for previous side
					//System.out.println(currentStep + "," + currentSide + "," + point);
					
					int x, y;
					switch (currentSide) {
						case 0: // top
							x = centreX + point;
							y = centreY + currentStep;
						break;
						case 1: // right
							x = centreX + currentStep;
							y = centreY - point;
						break;
						case 2: // bottom
							x = centreX - point;
							y = centreY - currentStep;
						break;
						default: // left
							x = centreX - currentStep;
							y = centreY + point;
						break;
					}
					
					UTMRef utm = new UTMRef(utmStart.getEasting() + (x * stepWidthMetres / 1), utmStart.getNorthing() + (y * stepWidthMetres / 1), utmStart.getLatZone(), utmStart.getLngZone());
					coords[pointer++] = utm.toLatLng();
				}
			}
		}
		
		return coords;
	}
	
	/**@return a 2D array, each element is an array of displaced points; first element is original track, later ones are increasing distance from it*/
	public static LatLng[][] coordsAroundPoints(LatLng[] originTrail, int maxStepsOut, double stepWidthMetres) {
		LatLng[][] rval1 = new LatLng[originTrail.length][];
		for (int i = 0; i < originTrail.length; i++) {
			rval1[i] = coordsAroundAPoint(originTrail[i], maxStepsOut, stepWidthMetres);
		}
		
		LatLng[][] rval2 = new LatLng[rval1[0].length][originTrail.length];
		for (int i = 0; i < originTrail.length; i++) {
			for (int j = 0; j < rval2.length; j++) {
				rval2[j][i] = rval1[i][j];
			}
		}
		
		return rval2;
	}
}
