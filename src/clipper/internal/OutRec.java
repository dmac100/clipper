package clipper.internal;

import clipper.PolyNode;

public class OutRec {
	public int idx;
	public boolean isHole;
	public OutRec firstLeft;
	public OutPt pts;
	public OutPt bottomPt;
	public PolyNode polyNode;
};