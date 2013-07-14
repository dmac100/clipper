package clipper;

import java.util.ArrayList;
import java.util.List;

public class PolyTree extends PolyNode {
	List<PolyNode> m_AllPolys = new ArrayList<PolyNode>();

	public void clear() {
		m_AllPolys.clear();
		m_Childs.clear();
	}

	public PolyNode getFirst() {
		if(m_Childs.size() > 0)
			return m_Childs.get(0);
		else
			return null;
	}

	public int getTotal() {
		return m_AllPolys.size();
	}
}