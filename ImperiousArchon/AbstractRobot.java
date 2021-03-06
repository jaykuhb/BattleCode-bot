package ImperiousArchon;
import battlecode.common.*;

import static ImperiousArchon.Utils.HELP_CHANNEL;
import static ImperiousArchon.Utils.REPORT_CHANNEL;

/**
 * Base class for all the robots in the game.
 */
abstract class AbstractRobot {

    int startRound;
    MapLocation startLocation;
    MapLocation currentLocation;
    MapLocation lastLocation;
    float currentSpeed;
    Direction currentDirection;
    int currentRound;
    float defenceDist;
    MapLocation[] ourInitialArchonLocations;
    MapLocation[] enemyInitialArchonLocations;
    MapLocation ourArchonsCentroid, enemyArchonsCentroid;
    float enemyCentroidDirection;
    RobotController rc;
    RobotInfo[] robots; //Cached result from senseNearbyRobots
    TreeInfo[] trees; //Cached result from senseNearbyTree
    BulletInfo[] bullets; //Cached result from senseNearbyBullets
    MapLocation rallyPoint = null;
    Team ourTeam, enemyTeam;
    boolean avoidingObstacle = false;
    boolean wentLeft = false;

    boolean blocked = false;
    boolean left = false;
    RobotType myType;


    AbstractRobot(RobotController rc) {
        this.rc = rc;

        myType = rc.getType();
        ourTeam = rc.getTeam();
        enemyTeam = ourTeam.opponent();
        startLocation = rc.getLocation();
        startRound = rc.getRoundNum();
        ourInitialArchonLocations = rc.getInitialArchonLocations(ourTeam);
        enemyInitialArchonLocations = rc.getInitialArchonLocations(enemyTeam);
        ourArchonsCentroid = countCentroid(ourInitialArchonLocations);
        enemyArchonsCentroid = countCentroid(enemyInitialArchonLocations);
        Direction _enemyCentroidDirection = ourArchonsCentroid.directionTo(enemyArchonsCentroid);
        if (_enemyCentroidDirection.radians < 0) {
            enemyCentroidDirection = (float) (_enemyCentroidDirection.radians + 2 * Math.PI);
        } else {
            enemyCentroidDirection = _enemyCentroidDirection.radians;
        }
//        defenceDist = Math.max(40, ourArchonsCentroid.distanceTo(enemyArchonsCentroid) * 0.3f);
        defenceDist = Math.max(20, getFarthestEnemyArchonDistance() * 0.3f);
    }

    private MapLocation countCentroid(MapLocation[] locations) {
        float x = 0, y = 0;
        for (MapLocation location : locations) {
            x += location.x;
            y += location.y;
        }

        return new MapLocation(x / locations.length, y / locations.length);
    }

    void broadCastLocation(int offset, MapLocation loc) throws GameActionException
    {
        rc.broadcastFloat(offset,loc.x);
        rc.broadcastFloat(offset+1,loc.y);
    }

    MapLocation readBroadCastLocation(int offset) throws  GameActionException
    {
        float x = rc.readBroadcastFloat(offset);
        float y = rc.readBroadcastFloat(offset+1);
        return new MapLocation(x,y);
    }

    void callHelp(MapLocation loc, float allyPower, float enemyPower) throws GameActionException {
        int round = rc.readBroadcast(HELP_CHANNEL);
        if (round == rc.getRoundNum())
            //Someone else called for help this turn
        {
            float otherAllyPower = rc.readBroadcastFloat(HELP_CHANNEL +2);
            float otherEnemyPower = rc.readBroadcastFloat(HELP_CHANNEL +3);
            if (otherAllyPower/1.7 > allyPower)
                //we are minor, dont call for help
                return;
            else  if (otherAllyPower>allyPower/2 && otherAllyPower/otherEnemyPower>0.65 && allyPower/enemyPower < 0.35)
                //they are considerable and we are loosing hard, dont call for help
                return;
        }

        rc.broadcastInt(HELP_CHANNEL, currentRound);
        rc.broadcastInt(HELP_CHANNEL +1, 1);
        rc.broadcastFloat(HELP_CHANNEL +2, allyPower);
        rc.broadcastFloat(HELP_CHANNEL +3, enemyPower);
        broadCastLocation(HELP_CHANNEL +4, loc);
    }

    boolean checkHelpCalls() throws GameActionException {
        int round = rc.readBroadcast(HELP_CHANNEL);
        int active = rc.readBroadcast(HELP_CHANNEL +1);

        if (active > 0 && currentRound - round < 50)
        {
            if (rallyPoint != null && rc.canSenseLocation(rallyPoint)) {
                boolean found = false;
                for (RobotInfo robot : robots) {
                    if (robot.team == enemyTeam)
                        found = true;
                }
                if (!found) {
                    cancelBroadcast(HELP_CHANNEL);
                    return false;
                }
            }
            rallyPoint = readBroadCastLocation(HELP_CHANNEL + 4);
            return true;
        } else {
            cancelBroadcast(HELP_CHANNEL);
            return false;
        }
    }

    boolean checkReports() throws GameActionException
    {
        int round = rc.readBroadcast(REPORT_CHANNEL);
        int active = rc.readBroadcast(REPORT_CHANNEL +1);
        if (currentRound - round < 120 && active > 0)
        {
            if (rallyPoint != null && rc.canSenseLocation(rallyPoint)) {
                boolean found = false;
                for (RobotInfo robot : robots)
                {
                    if (robot.team == enemyTeam)
                        found = true;
                }
                if (!found) {
                    cancelBroadcast(REPORT_CHANNEL);
                    return false;
                }
            }
            rallyPoint = readBroadCastLocation(REPORT_CHANNEL +3);
            return true;
        } else {
            cancelBroadcast(REPORT_CHANNEL);
            return false;
        }
    }

    private void cancelBroadcast(int offset) throws GameActionException {
        rc.broadcast(offset, rc.getRoundNum());
        rc.broadcast(offset+1, 0);
        rallyPoint = null;
    }

    /**
     * Handles the whole donating strategy.
     */
    private void handleDonation() {
        // Go for the win if we have enough bullets
        int vps = rc.getTeamVictoryPoints();
        float bullets = rc.getTeamBullets();
        float exchangeRate =  rc.getVictoryPointCost();

        try {
            /* Game ends on the next turn, or we have enough bullets to win right now */
            if (rc.getRoundNum() >= rc.getRoundLimit() - 1
                    || (int) (bullets / exchangeRate) + vps >= GameConstants.VICTORY_POINTS_TO_WIN) {
                rc.donate(bullets);
            } else if (bullets > 800 && rc.getRoundNum() > 200) {
                /* We have surplus of bullets, let's donate them */
                int newVps = (int) ((bullets - 800) / exchangeRate);
                rc.donate(newVps * exchangeRate);
            }
        } catch (GameActionException e) {
            System.err.println("Improper amount for donation! " + e.getMessage());
        }
    }

    void makePath(Float direction, int sightRange) {
        MapLocation origin = currentLocation;
        final int step = 2;
        int row;
        for (int i = -sightRange; i < sightRange; i += step) {
            row = 0;
            for (int j = -sightRange; j < sightRange; j += step) {
                MapLocation shift = origin.translate(i + ((row % 2 == 0) ? -0 / 2f : 0f), j);
                if (origin.distanceTo(shift) < sightRange) {
                    if (rc.canMove(shift)) {
                        indicate(shift, 10, 10, 128);
                    } else {
                        indicate(shift, 128, 10, 10);
                    }
                }
                ++row;
            }
        }
        indicate(origin.add(direction), 10, 128, 10);
        try {
            tryMove(origin.add(direction));
        } catch (GameActionException e) {
            e.printStackTrace();
        }
//        rc.canMove()
    }

    float getClosestOurArchonDistance() {
        float dist = Float.POSITIVE_INFINITY;
        for (MapLocation ourInitialArchonLocation : ourInitialArchonLocations) {
            float _dist = rc.getLocation().distanceTo(ourInitialArchonLocation);
            if (_dist < dist) {
                dist = _dist;
            }
        }
        return dist;
    }

    private float getFarthestEnemyArchonDistance() {
        float dist = Float.NEGATIVE_INFINITY;
        for (MapLocation ourInitialArchonLocation : enemyInitialArchonLocations) {
            float _dist = rc.getLocation().distanceTo(ourInitialArchonLocation);
            if (_dist > dist) {
                dist = _dist;
            }
        }
        return dist;
    }

    float getClosestEnemyArchonDistance() {
        float dist = Float.POSITIVE_INFINITY;
        for (MapLocation enemyInitialArchonLocation : enemyInitialArchonLocations) {
            float _dist = rc.getLocation().distanceTo(enemyInitialArchonLocation);
            if (_dist < dist) {
                dist = _dist;
            }
        }
        return dist;
    }

    void indicate(MapLocation loc, int R, int G, int B) {
        if (RobotPlayer.DEBUG) {
            rc.setIndicatorDot(loc, R, G, B);
        }
    }

    void indicateLine(MapLocation from, MapLocation to, int R, int G, int B) {
        if (RobotPlayer.DEBUG) {
            rc.setIndicatorLine(from, to, R, G, B);
        }
    }

    abstract void run() throws GameActionException;

    private void preCalculate() {
        robots = rc.senseNearbyRobots();
        trees = rc.senseNearbyTrees();
        bullets = rc.senseNearbyBullets();
        currentLocation = rc.getLocation();
        currentRound = rc.getRoundNum();
        if (lastLocation != null) {
            currentSpeed = lastLocation.distanceSquaredTo(currentLocation);
            currentDirection = lastLocation.directionTo(currentLocation);
        }
    }

    /**
     * Should be called at the start of the main robot round loop.
     */
    void preloop() throws GameActionException {
        handleDonation();
        preCalculate();
        readBroadcast();
    }

    void checkShake() throws GameActionException
    {

        if (!rc.canShake())
            return;
        for (TreeInfo t:trees)
        {
            if (t.getContainedBullets() > 0)
            {
                if (rc.canShake(t.getID()))
                    rc.shake(t.getID());
                else
                    tryMove(t.getLocation());
                return;
            }
        }
    }

    /**
     * Should be called at the end of the main robot round loop.
     */
    void postloop() {
//        System.out.print(Clock.getBytecodesLeft());
        lastLocation = currentLocation;

        /* Clock.yield() makes the robot wait until the next turn,
         then it will perform this loop again */
        Clock.yield();
    }

    /*
   * Checks to see if we can move here
   * Uses rc.canMove and then performs extra checks for a TANK unit as we don't want to destroy our own trees
   */
    boolean canMove(MapLocation dest) throws GameActionException {

        if (!rc.canMove(dest))
            return false;
        return true;
    }

    boolean moveTo(MapLocation dest) throws GameActionException {

       return tryMove(dest);
    }

    abstract void readBroadcast() throws GameActionException;

    float lumberjackRange() {
        return rc.getType().bodyRadius + RobotType.LUMBERJACK.strideRadius + GameConstants.LUMBERJACK_STRIKE_RADIUS;
    }


    void randomWalk() throws GameActionException {
        Direction dir = Utils.randomDirection();
        tryMove(rc.getLocation().add(dir, rc.getType().strideRadius), 0, 0, 0);
    }

    boolean tryMove(MapLocation to) throws GameActionException {
        indicateLine(currentLocation, to, 16, 128, 16);
        return tryMove(to, 25, 6, 6);
    }

    boolean tryMove(MapLocation to, float degreeOffset, int checksLeft, int checksRight) throws GameActionException {
        if (rc.hasMoved() || to == null)
            return false;

        MapLocation here = rc.getLocation();
        Direction dir = here.directionTo(to);

        float dist = here.distanceTo(to);
        MapLocation dest = to;

        if (dir == null || dist <= 0 || here.equals(to))
            return true;

        if (dist > rc.getType().strideRadius)
        {
            dist = rc.getType().strideRadius;
            dest = here.add(dir, dist);
        }

        MapLocation bestUnsafe = null;
        float leastDamage = 1000;
        float damage;

        // First, try intended direction
        if (canMove(dest))
        {
            damage = damageAtLocation(dest);
            if (damage > 0 && damage < leastDamage)
            {
                leastDamage = damage;
                bestUnsafe = dest;
            }
            if (damage < 0)
            {
                rc.move(dest);
                avoidingObstacle = false;
               // indicate(dest,0,0,255);
                return true;
            }
        }

        // Now try a bunch of similar angles
        int currentCheck = 1;
        int checksPerSide = Math.max(checksLeft, checksRight);

        if (avoidingObstacle && wentLeft)
        {
            // Try the offset of the left side
            while (currentCheck <= checksLeft) {
                dest = here.add(dir.rotateLeftDegrees(degreeOffset * currentCheck), dist);
                if (canMove(dest)) {
                    damage = damageAtLocation(dest);
                    if (damage > 0 && damage < leastDamage) {
                        leastDamage = damage;
                        bestUnsafe = dest;
                    }
                    if (damage < 0) {
                        rc.move(dest);
                        avoidingObstacle = true;
                        //indicate(dest, 0, 0, 255);
                        return true;
                    }
                }
                currentCheck++;
            }
        }
        else if (avoidingObstacle)
        {
            while (currentCheck <= checksRight)
            {
                dest = here.add(dir.rotateRightDegrees(degreeOffset*currentCheck), dist);
                if (canMove(dest))
                {
                    damage = damageAtLocation(dest);
                    if (damage > 0 && damage < leastDamage)
                    {
                        leastDamage = damage;
                        bestUnsafe = dest;
                    }
                    if (damage < 0)
                    {
                        rc.move(dest);
                        avoidingObstacle = true;
                        //indicate(dest,0,0,255);
                        return true;
                    }
                }
                currentCheck++;
            }
        }

        currentCheck = 1;
        while(currentCheck<=checksPerSide)
        {
            // Try the offset of the left side
            if (currentCheck <= checksLeft)
            {
                dest = here.add(dir.rotateLeftDegrees(degreeOffset*currentCheck), dist);
                if (canMove(dest))
                {
                    damage = damageAtLocation(dest);
                    if (damage > 0 && damage < leastDamage)
                    {
                        leastDamage = damage;
                        bestUnsafe = dest;
                    }
                    if (damage < 0)
                    {
                        rc.move(dest);

                        avoidingObstacle = true;
                        wentLeft = true;
                        //indicate(dest,0,0,255);
                        return true;
                    }
                }
            }

            // Try the offset on the right side
            if (currentCheck <= checksRight)
            {
                dest = here.add(dir.rotateRightDegrees(degreeOffset*currentCheck), dist);
                if (canMove(dest))
                {
                    damage = damageAtLocation(dest);
                    if (damage > 0 && damage < leastDamage)
                    {
                        leastDamage = damage;
                        bestUnsafe = dest;
                    }
                    if (damage < 0)
                    {
                        rc.move(dest);
                        avoidingObstacle = true;
                        wentLeft = false;
                        //indicate(dest,0,0,255);
                        return true;
                    }
                }
            }
            // No move performed, try slightly further
            currentCheck++;
        }

        if (bestUnsafe != null && leastDamage <= damageAtLocation(here) && rc.canMove(bestUnsafe))
        { //Not safe here so happy to move to another unsafe place
            rc.move(bestUnsafe);
            //setIndicator(here, bestUnsafe, 255, 0, 0);
            return true;
        }

        // A move never happened, so return false.
        return false;
    }
    /*
    * shoot works out the optimum fire pattern based on the size of the target and its distance from us then shoots
    * avoiding friendly fire
    *
    * If the enemy is really close we may have a guaranteed hit of one or more bullets depending on its stride
    * If it is further away we may need to fire multiple shots to guarantee hitting it with one bullet
    */
    void shoot(RobotInfo target) throws GameActionException
    {

        if (target == null || !haveAmmo())
            return;

        MapLocation targetLoc = target.getLocation();
        MapLocation myLocation = rc.getLocation();
        Direction dir = myLocation.directionTo(targetLoc);
        float dist = myLocation.distanceTo(targetLoc);
        int shot = processShot(dir, target);

        if (shot < 0)
        {
            rc.setIndicatorLine(rc.getLocation(),target.getLocation() ,255,0,0);
            //System.out.print("couldnt fire");
            return;
        }
        //Look at the distance to target and its size to determine if it can dodge
        //Pentad fires 5 bullets with 15 degrees between each one (spread originating from the centre of the robot firing)
        //Triad fires 3 bullets with 20 degrees between each one
        //We can work out the angle either side of the centre of the target at which we hit
        float spreadAngle = (float) Math.asin(target.getType().bodyRadius/dist);
        int shotsToFire = 0;
        Direction shotDir = dir;
        //debug(3, "shoot: target " + target +  " dist=" + dist + " spreadAngle = " + spreadAngle + " (" + Math.toDegrees((double)spreadAngle) + ")");
        if (shot == 1)
        { //can be dodged
            if (rc.canFireTriadShot() && dist <= target.getType().bodyRadius / Math.sin(Math.toRadians(GameConstants.TRIAD_SPREAD_DEGREES/2)))
            {
                shotsToFire = 3;
            }
            else if (rc.canFirePentadShot() && dist <= target.getType().bodyRadius / Math.sin(Math.toRadians(GameConstants.PENTAD_SPREAD_DEGREES/2)))
            {
                shotsToFire = 5;
            }
        }
        else if (rc.canFirePentadShot() && 2*spreadAngle >= Math.toRadians(GameConstants.PENTAD_SPREAD_DEGREES*4))
        { //All 5 shots will hit
            shotsToFire = 5;

        }
        else if (rc.canFirePentadShot() && 2*spreadAngle > Math.toRadians(GameConstants.PENTAD_SPREAD_DEGREES*3))
        { //4 shots will hit
            shotsToFire = 5;
            shotDir.rotateRightDegrees(GameConstants.PENTAD_SPREAD_DEGREES/2);
        }
        else if (rc.canFireTriadShot() && 2*spreadAngle > Math.toRadians(GameConstants.TRIAD_SPREAD_DEGREES*2))
        { //All 3 triad shots will hit
            shotsToFire = 3;
        }
        else if (rc.canFirePentadShot() && 2*spreadAngle > Math.toRadians(GameConstants.PENTAD_SPREAD_DEGREES*2))
        { //3 of 5 shots will hit)
            shotsToFire = 5;
        }
        else if (rc.canFireTriadShot() && 2*spreadAngle > Math.toRadians(GameConstants.TRIAD_SPREAD_DEGREES*2))
        { //2 of a triad shots will hit
            shotsToFire = 3;
            shotDir.rotateLeftDegrees(GameConstants.TRIAD_SPREAD_DEGREES/2);

        }

        else if (rc.canFirePentadShot() && 2*spreadAngle > Math.toRadians(GameConstants.PENTAD_SPREAD_DEGREES))
        { //2 of 5 shots will hit
            shotsToFire = 5;
            shotDir.rotateRightDegrees(GameConstants.PENTAD_SPREAD_DEGREES/2);
            //debug (3, "Firing 5 - 2 should hit");
        }
        else if (rc.canFireSingleShot())
        {
            shotsToFire = 1;
            rc.fireSingleShot(shotDir);
        }
        if (shotsToFire == 5)
        {
            rc.firePentadShot(shotDir);
        }
        else if (shotsToFire == 3)
        {
            rc.fireTriadShot(shotDir);
        }
        if (shotsToFire > 0)
        { //We shot so update bullet info
            bullets = rc.senseNearbyBullets();
        }
    }


    /*
     * Check to see if a bullet fired from here will hit an enemy first (rather than a tree or an ally)
     * 2: hit
     * 1: might miss
     * 0: enemy tree
     * -1: miss
     * -2: neutral tree
     * -3: ally tree
     * -4: ally unit
     */
    int processShot(Direction dir, RobotInfo target)
    {
        float hitDist=-5;
        int result= willHit(rc.getLocation().add(dir, rc.getType().bodyRadius + GameConstants.BULLET_SPAWN_OFFSET), dir,hitDist);

        if (result <=0)
            return result;

        int turnsBeforeHit = (int) Math.ceil(hitDist/ rc.getType().bulletSpeed);
        if (hitDist % rc.getType().bulletSpeed == 0)
            turnsBeforeHit--;
        if (turnsBeforeHit * target.getType().strideRadius <= target.getType().bodyRadius)
            return 2;

        return 1; //Bullet will probably miss (can be dodged)
    }

    boolean canHit(RobotInfo target)
    {
        MapLocation targetLoc = target.getLocation();
        MapLocation myLocation = rc.getLocation();
        Direction dir = myLocation.directionTo(targetLoc);
        float dist = myLocation.distanceTo(targetLoc);
        int shot = processShot(dir, target);

        if (shot < 0)
            return false;
        return true;
    }
    boolean canShootMe(RobotInfo enemy)
    {
        float hitDist=-5;
        MapLocation loc = enemy.getLocation();
        Direction dir =  loc.directionTo(rc.getLocation());
        float dist = loc.distanceTo(rc.getLocation());
        int result= willHit(loc.add(dir, rc.getType().bodyRadius + GameConstants.BULLET_SPAWN_OFFSET), dir,hitDist);

        if (result == -4 && dist > hitDist -0.0001 && dist < hitDist + 0.0001)
            return true;
        return false;
    }
    boolean haveAmmo()
    {
        float ammo = rc.getTeamBullets();
        return (ammo >= 1);
    }

    /*
     * Check to see if a bullet fired from here will hit an enemy first (rather than a tree or an ally)
     * 1: hit
     * 0: enemy tree
     * -1: miss
     * -2: neutral tree
     * -3: ally tree
     * -4: ally unit
     */
    int willHit(MapLocation loc, Direction dir, float dist) {
        TreeInfo nearestTree = null;
        float nearestHitTreeDist = -1;
        RobotInfo nearestUnit = null;
        float nearestHitUnitDist = -1;

        //Check each tree to see if it will hit it
        for (TreeInfo t:trees)
        {
            nearestHitTreeDist = Utils.calcHitDist(loc, loc.add(dir, rc.getType().sensorRadius*2), t.getLocation(), t.getRadius());
            if (nearestHitTreeDist >= 0)
            {
                nearestTree = t;
                break;
            }
        }

        for (RobotInfo r:robots)
        {
            nearestHitUnitDist = Utils.calcHitDist(loc, loc.add(dir, rc.getType().sensorRadius*2), r.getLocation(), r.getRadius());
            if (nearestHitUnitDist >= 0)
            {
                nearestUnit = r;
                break;
            }
        }

        if (nearestUnit != null && (nearestTree == null || nearestHitUnitDist <= nearestHitTreeDist))
        { //We hit a robot
            dist = nearestHitUnitDist;
            if (nearestUnit.getTeam() != rc.getTeam())
                return 1;
            else
                return -4;
        }

        if (nearestTree != null)
        {
            dist = nearestHitTreeDist;
            if (nearestTree.getTeam() == rc.getTeam().opponent())
                return 0;
            else if (nearestTree.getTeam() == rc.getTeam())
                return -3;
            else
                return -2;
        }

        return -1;
    }

    /*
    * Returns the bullet damage at this location
    */
    float bulletDamage(MapLocation loc)
    {
        float damage = 0;
        int cutOff = 7000;

        for (BulletInfo b:bullets) {
            //Will the bullet hit us?
            //Calc nearest point this bullet gets to us
            float angle = Math.abs(b.getLocation().directionTo(loc).radiansBetween(b.getDir()));
            if (angle < Math.PI / 2)
            {
                float hypot = b.getLocation().distanceTo(loc);
                float nearest = (float) (hypot * Math.sin(angle));
                if (nearest <= rc.getType().bodyRadius)
                    damage += b.getDamage();
            }

            if (Clock.getBytecodesLeft() < cutOff)
                break;
        }

        return damage;
    }

    int lumberjacksInRange(MapLocation loc) {
        int enemyLumberjacks = 0;


        float range =lumberjackRange();
        for (RobotInfo r:robots)
        {
            if (r.getType() == RobotType.LUMBERJACK && loc.distanceTo(r.getLocation()) <= range)
            {
                if (r.getTeam() != rc.getTeam())
                    enemyLumberjacks++;
            }
        }
        return enemyLumberjacks;
    }

    /*
    * damageAtLocation
    *
    * Works out if we will be shot at the given location
    * or if a lumberjack is in range
    * or if an enemy going after us can fire and hit us this turn
    *
    * Returns amount of damage we would take at this location
    */
    float damageAtLocation(MapLocation loc) throws GameActionException
    {
        float damage = -0.01f;

        int lumberjacks = lumberjacksInRange(loc);
        if (lumberjacks > 0)
        {
            damage += lumberjacks * RobotType.LUMBERJACK.attackPower;
        }

        float bullets = bulletDamage(loc);
        if (bullets > 0)
        {
            damage += bullets;
        }


        Team enemy = rc.getTeam().opponent();
        float startDamage = damage;
        for (RobotInfo r:robots)
        {
            if (r.getTeam() == enemy && r.getType().bulletSpeed > 0)
            { //Only care about robots that can shoot
                float dist = r.getLocation().distanceTo(loc) - rc.getType().bodyRadius;
                float range = r.getType().bodyRadius + GameConstants.BULLET_SPAWN_OFFSET + r.getType().bulletSpeed + r.getType().strideRadius;
                if (range >= dist)
                {
                    damage += r.getType().attackPower;
                }
            }
        }
        //if (damage > 0)
        //	setIndicator(loc, 128, 0, 0);


        return damage;
    }

    boolean canBeat(RobotInfo enemy)
    {
        if (!rc.getType().canAttack())
            return false;
        if (!enemy.getType().canAttack())
            return true;

        if (rc.getType()!=RobotType.LUMBERJACK && enemy.type == RobotType.LUMBERJACK)
            return true;

        int turnsToKill = (int) (enemy.getHealth() / rc.getType().attackPower);
        int turnsToDie = (int) (rc.getHealth() / enemy.getType().attackPower);
        return turnsToKill <= turnsToDie;
    }

    boolean canBeatEasily(RobotInfo enemy)
    {
        if (!rc.getType().canAttack())
            return false;
        if (!enemy.getType().canAttack())
            return true;
        int turnsToKill = (int) (enemy.getHealth() / rc.getType().attackPower);
        int turnsToDie = (int) (rc.getHealth() / enemy.getType().attackPower);
        return 2*turnsToKill <= turnsToDie;
    }

    boolean canSenseMe(RobotInfo enemy)
    {
        return  (enemy.getType().sensorRadius > enemy.getLocation().distanceTo(rc.getLocation()));
    }

    Direction buildingDirection(RobotType type, int maxAttempts, float angularOffset)
    {
        if (angularOffset == 0) {
            angularOffset = 360f / maxAttempts;
        }

        Direction enemyCentroidDirection = ourArchonsCentroid.directionTo(enemyArchonsCentroid);
        for (int i =0; i <maxAttempts; i++) {
            Direction buildDir = enemyCentroidDirection.rotateLeftDegrees(angularOffset*i);
            if (rc.canBuildRobot(type, buildDir))
            {
                return buildDir;
            }
        }
        return null;
    }

    Direction hiringDirection(int maxAttempts, float angularOffset)
    {
        Direction enemyCentroidDirection = ourArchonsCentroid.directionTo(enemyArchonsCentroid);
        for (int i =0; i <maxAttempts; i++) {
            Direction buildDir = enemyCentroidDirection.rotateLeftDegrees(angularOffset*i);
            if (rc.canHireGardener(buildDir))
            {
                return buildDir;
            }
        }
        return null;
    }

    int numNotOurTreesVisible() {
        int numNotOursTreesVisible = 0;
        for (TreeInfo tree : trees) {
            if (tree.team != ourTeam) {
                ++numNotOursTreesVisible;
            }
        }
        return numNotOursTreesVisible;
    }

    void checkHelpNeeds() throws GameActionException {
        float enemyPower = 0f;
        float allyPower = 0f;

        RobotInfo threat = null;
        for (RobotInfo r : robots) {
            if (r.getTeam() == enemyTeam) {
                if (threat ==null)
                    threat = r;
                enemyPower += Utils.unitStrength(r.type);
            } else {
                allyPower += Utils.unitStrength(r.type);
            }
        }

        if (enemyPower > 0) {
            callHelp(threat.location, allyPower, enemyPower);
        }
    }

}
