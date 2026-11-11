package openrsc.livemap.planner;

import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.livemap.assets.MapNav;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.planner.PathfinderAccount;
import openrsc.nav.planner.PathfinderInputs;
import openrsc.nav.planner.PathfinderPlans;
import openrsc.nav.planner.PathfinderReach;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The route planner over HTTP, on the map's pathfinder ({@link MapNav#pathfinder}): what its editor offers
 * ({@code GET /api/pathfinder/inputs}), a plan ({@code POST …/plan}), the cheaper preview ({@code …/preview}), the
 * walking components reachable from a start ({@code …/reachable}) and the gate facts of the account
 * ({@code …/account}). A request whose {@code as} names an account plans as the application's {@link PlannerAccounts}
 * has it, under the routing they give it, and is answered 404 for a name they do not know; a request the planner
 * cannot read is answered 400.
 */
@RestController
@RequestMapping("/api/pathfinder")
public class PathfinderController {

    private final MapNav nav;
    private final @Nullable PlannerAccounts accounts;

    public PathfinderController(MapNav nav, ObjectProvider<PlannerAccounts> accounts) {
        this.nav = nav;
        this.accounts = accounts.getIfAvailable();
    }

    @GetMapping("/inputs")
    public PathfinderInputs.Response inputs() {
        return PathfinderInputs.of(nav.pathfinder());
    }

    @PostMapping("/plan")
    public ResponseEntity<PathfinderPlans.Response> plan(@RequestBody PathfinderPlans.Request request) {
        return answer(request, PathfinderPlans::plan);
    }

    @PostMapping("/preview")
    public ResponseEntity<PathfinderPlans.Response> preview(@RequestBody PathfinderPlans.Request request) {
        return answer(request, PathfinderPlans::preview);
    }

    @PostMapping("/reachable")
    public ResponseEntity<PathfinderReach.Response> reachable(@RequestBody PathfinderPlans.Request request) {
        return answer(request, PathfinderReach::of);
    }

    @PostMapping("/account")
    public ResponseEntity<PathfinderAccount.Response> account(@RequestBody PathfinderPlans.Request request) {
        return answer(request, PathfinderAccount::of);
    }

    private interface Answer<T> {
        T apply(Pathfinder pf, @Nullable AccountFacts as, PathfinderPlans.Request request);
    }

    // A plan as a named account takes the routing the application gives it.
    private <T> ResponseEntity<T> answer(PathfinderPlans.Request request, Answer<T> answer) {
        AccountFacts as = null;
        String name = request.as();
        Pathfinder pf = nav.pathfinder();
        PathfinderPlans.Request asked = request;
        if (name != null && !name.isBlank()) {
            as = accounts == null ? null : accounts.account(name);
            if (as == null || accounts == null) {
                return ResponseEntity.notFound().build();
            }
            NavPolicy routing =
                    accounts.routing(pf, name, request.policy() == null ? NavPolicy.DEFAULT : request.policy());
            asked = new PathfinderPlans.Request(
                    request.from(), request.to(), name, request.account(), request.loadout(), routing);
        }
        try {
            return ResponseEntity.ok(answer.apply(pf, as, asked));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
