package jadx.dex.visitors.regions;

import jadx.dex.attributes.AttributeType;
import jadx.dex.attributes.DeclareVariableAttr;
import jadx.dex.instructions.args.RegisterArg;
import jadx.dex.nodes.IBlock;
import jadx.dex.nodes.IContainer;
import jadx.dex.nodes.IRegion;
import jadx.dex.nodes.InsnNode;
import jadx.dex.nodes.MethodNode;
import jadx.dex.visitors.AbstractVisitor;
import jadx.utils.RegionUtils;
import jadx.utils.exceptions.JadxException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProcessVariables extends AbstractVisitor {
	private static final Logger LOG = LoggerFactory.getLogger(ProcessVariables.class);

	private static class Usage {
		private RegisterArg arg;
		private final Set<IRegion> usage = new HashSet<IRegion>(2);
		private final Set<IRegion> assigns = new HashSet<IRegion>(2);

		public void setArg(RegisterArg arg) {
			this.arg = arg;
		}

		public RegisterArg getArg() {
			return arg;
		}

		public Set<IRegion> getAssigns() {
			return assigns;
		}

		public Set<IRegion> getUseRegions() {
			return usage;
		}

		@Override
		public String toString() {
			return arg + " " + assigns + " " + usage;
		}
	}

	@Override
	public void visit(MethodNode mth) throws JadxException {
		final Map<RegisterArg, Usage> usageMap = new HashMap<RegisterArg, Usage>();

		// collect all variables usage
		IRegionVisitor collect = new TracedRegionVisitor() {
			@Override
			public void processBlockTraced(MethodNode mth, IBlock container, IRegion curRegion) {
				int len = container.getInstructions().size();
				List<RegisterArg> args = new ArrayList<RegisterArg>();
				for (int i = 0; i < len; i++) {
					InsnNode insn = container.getInstructions().get(i);
					// result
					RegisterArg result = insn.getResult();
					if (result != null) {
						Usage u = usageMap.get(result);
						if (u == null) {
							u = new Usage();
							u.setArg(result);
							usageMap.put(result, u);
						}
						if (u.getArg() == null)
							u.setArg(result);
						u.getAssigns().add(curRegion);
					}
					// args
					args.clear();
					insn.getRegisterArgs(args);
					for (RegisterArg arg : args) {
						Usage u = usageMap.get(arg);
						if (u == null) {
							u = new Usage();
							usageMap.put(arg, u);
						}
						u.getUseRegions().add(curRegion);
					}
				}
			}
		};
		DepthRegionTraverser.traverseAll(mth, collect);

		List<RegisterArg> mthArgs = mth.getArguments(true);
		// reduce assigns map
		for (Iterator<Entry<RegisterArg, Usage>> it = usageMap.entrySet().iterator(); it.hasNext();) {
			Entry<RegisterArg, Usage> entry = it.next();
			Usage u = entry.getValue();
			RegisterArg r = u.getArg();

			if (u.getAssigns().isEmpty()) {
				it.remove();
				continue;
			}

			int i;
			if ((i = mthArgs.indexOf(r)) != -1) {
				// if (mthArgs.get(i).getTypedVar() == r.getTypedVar()) {
				it.remove();
				continue;
				// }
			}
			// check if we can declare variable at current assigns
			for (IRegion assignRegion : u.getAssigns()) {
				if (canDeclareInRegion(u, assignRegion)) {
					r.getParentInsn().getAttributes().add(new DeclareVariableAttr());
					it.remove();
					break;
				}
			}
		}

		// apply
		for (Entry<RegisterArg, Usage> entry : usageMap.entrySet()) {
			Usage u = entry.getValue();
			// find common region which contains all usage regions

			Set<IRegion> set = u.getUseRegions();
			for (Iterator it = set.iterator(); it.hasNext();) {
				IRegion r = (IRegion) it.next();
				IRegion parent = r.getParent();
				if (parent != null && set.contains(parent))
					it.remove();
			}
			if (set.isEmpty())
				continue;

			IRegion region = set.iterator().next();
			IRegion parent = region;
			boolean declare = false;
			while (parent != null) {
				if (canDeclareInRegion(u, region)) {
					declareVar(region, u.getArg());
					declare = true;
					break;
				}
				region = parent;
				parent = region.getParent();
			}

			if (!declare) {
				declareVar(mth.getRegion(), u.getArg());
			}
		}
	}

	private void declareVar(IContainer region, RegisterArg arg) {
		DeclareVariableAttr dv = (DeclareVariableAttr) region.getAttributes().get(
				AttributeType.DECLARE_VARIABLE);
		if (dv == null) {
			dv = new DeclareVariableAttr(new ArrayList<RegisterArg>());
			region.getAttributes().add(dv);
		}
		dv.addVar(arg);
	}

	private boolean canDeclareInRegion(Usage u, IRegion region) {
		for (IRegion r : u.getAssigns()) {
			if (!RegionUtils.isRegionContainsRegion(region, r))
				return false;
		}
		for (IRegion r : u.getUseRegions()) {
			if (!RegionUtils.isRegionContainsRegion(region, r))
				return false;
		}
		return true;
	}
}