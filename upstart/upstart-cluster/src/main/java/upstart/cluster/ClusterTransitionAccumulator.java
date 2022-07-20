package upstart.cluster;

import upstart.util.concurrent.BatchAccumulator;
import upstart.util.concurrent.Deadline;
import upstart.util.concurrent.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static upstart.util.concurrent.BatchAccumulator.AccumulationResult.accepted;

@Singleton
public class ClusterTransitionAccumulator implements ClusterTransitionListener {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterTransitionAccumulator.class);

  private final BatchAccumulator<ClusterMembershipTransition> accumulator;

  @Inject
  public ClusterTransitionAccumulator(
          ClusterMembershipListener membershipListener,
          ClusterMembershipConfig config,
          Scheduler scheduler
  ) {
    this.accumulator = new BatchAccumulator<>(
            ClusterMembershipTransition::new,
            membershipListener::onClusterMembershipChanged,
            config.idleTransitionTimeout(),
            config.maxTransitionDelay(),
            scheduler
    );
  }

  @Override
  public void onNodesJoined(List<ClusterNodeId> nodeIds) {
    Deadline delay = accumulator.accumulate(nodeIds, (nodes, transition) -> {
      for (ClusterNodeId node : nodes) {
        transition.nodeJoined(node);
      }
      return accepted();
    });
    LOG.info("Discovered new node(s), waiting at least {}s to process: {}", delay.remaining().toSeconds(), nodeIds);
  }

  @Override
  public void onNodeLeft(ClusterNodeId nodeId) {
    Deadline delay = accumulator.accumulate(nodeId, (node, transition) -> {
      transition.nodeDeparted(node);
      return accepted();
    });
    LOG.info("Discovered departed node, waiting at least {}s to process: {}", delay.remaining().toSeconds(), nodeId);
  }
}

