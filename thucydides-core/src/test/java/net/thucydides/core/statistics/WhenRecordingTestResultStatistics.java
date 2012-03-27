package net.thucydides.core.statistics;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Singleton;
import net.thucydides.core.annotations.WithTag;
import net.thucydides.core.guice.DatabaseConfig;
import net.thucydides.core.guice.EnvironmentVariablesDatabaseConfig;
import net.thucydides.core.guice.ThucydidesModule;
import net.thucydides.core.logging.ThucydidesLogging;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.model.TestResult;
import net.thucydides.core.pages.InternalSystemClock;
import net.thucydides.core.pages.SystemClock;
import net.thucydides.core.statistics.dao.HibernateTestOutcomeHistoryDAO;
import net.thucydides.core.statistics.dao.TestOutcomeHistoryDAO;
import net.thucydides.core.statistics.integration.db.LocalDatabase;
import net.thucydides.core.statistics.integration.db.LocalHSqldbDatabase;
import net.thucydides.core.statistics.model.TestRun;
import net.thucydides.core.statistics.model.TestRunTag;
import net.thucydides.core.statistics.model.TestStatistics;
import net.thucydides.core.steps.ConsoleLoggingListener;
import net.thucydides.core.steps.StepListener;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.MockEnvironmentVariables;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.persistence.EntityManager;
import java.util.List;

import static net.thucydides.core.matchers.dates.DateMatchers.isSameAs;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

public class WhenRecordingTestResultStatistics {

    Injector injector;
    EnvironmentVariables environmentVariables;
    ThucydidesModuleWithMockEnvironmentVariables guiceModule;
    StatisticsListener statisticsListener;
    TestStatisticsProvider testStatisticsProvider;

    class ThucydidesModuleWithMockEnvironmentVariables extends ThucydidesModule {
        @Override
        protected void configure() {
            clearEntityManagerCache();
            bind(SystemClock.class).to(InternalSystemClock.class).in(Singleton.class);
            bind(EnvironmentVariables.class).to(MockEnvironmentVariables.class).in(Singleton.class);
            bind(LocalDatabase.class).to(LocalHSqldbDatabase.class).in(Singleton.class);
            bind(DatabaseConfig.class).to(EnvironmentVariablesDatabaseConfig.class).in(Singleton.class);
            bind(TestOutcomeHistoryDAO.class).to(HibernateTestOutcomeHistoryDAO.class);
            bind(StepListener.class).annotatedWith(Statistics.class).to(StatisticsListener.class);
            bind(StepListener.class).annotatedWith(ThucydidesLogging.class).to(ConsoleLoggingListener.class);
        }
    }

    @Mock
    TestOutcome testOutcome;

    @Mock
    SystemClock clock;

    TestOutcomeHistoryDAO testOutcomeHistoryDAO;

    static final DateTime JANUARY_1ST_2012 = new DateTime(2012, 1, 1, 0, 0);

    @WithTag(value="Online sales", type="feature")
    class CarSalesTestCaseSample {
        @WithTag(value="Car sales", type="feature")
        public void car_sales_test() {}
    }

    @Before
    public void initListener() {
        MockitoAnnotations.initMocks(this);

        when(testOutcome.getTitle()).thenReturn("Some test");
        when(testOutcome.getResult()).thenReturn(TestResult.SUCCESS);
        when(testOutcome.getMethodName()).thenReturn("car_sales_test");
        when(testOutcome.getStoryTitle()).thenReturn("A Test Story");
        when(testOutcome.getDuration()).thenReturn(500L);

        guiceModule = new ThucydidesModuleWithMockEnvironmentVariables();
        injector = Guice.createInjector(guiceModule);
        environmentVariables = injector.getInstance(EnvironmentVariables.class);
        environmentVariables.setProperty("thucydides.statistics.url", "jdbc:hsqldb:mem:testDatabase");
        environmentVariables.setProperty("thucydides.record.statistics","true");

        testOutcomeHistoryDAO = injector.getInstance(HibernateTestOutcomeHistoryDAO.class);
        statisticsListener = new StatisticsListener(testOutcomeHistoryDAO, environmentVariables);
        testStatisticsProvider = new TestStatisticsProvider(testOutcomeHistoryDAO);

        prepareTestData(statisticsListener);
    }

    @Test
    public void should_be_able_to_obtain_the_statistics_listener_via_guice() {
        StepListener statisticsListener = injector.getInstance(Key.get(StepListener.class, Statistics.class));
        assertThat(statisticsListener, instanceOf(StatisticsListener.class));
    }

    @Test
    public void should_record_test_results_for_posterity() {

        prepareDAOWithFixedClock();

        when(testOutcome.getResult()).thenReturn(TestResult.SUCCESS);

        statisticsListener.testFinished(testOutcome);
        statisticsListener.testSuiteFinished();

        List<TestRun> storedTestRuns = testStatisticsProvider.testRunsForTest(With.title(testOutcome.getTitle()));
        assertThat(storedTestRuns.size(), greaterThan(0));

        TestRun lastTestRun = storedTestRuns.get(storedTestRuns.size() - 1);
        assertThat(lastTestRun.getTitle(), is(testOutcome.getTitle()));
        assertThat(lastTestRun.getExecutionDate(), isSameAs(JANUARY_1ST_2012.toDate()));
        assertThat(lastTestRun.getDuration(), is(testOutcome.getDuration()));
    }

    @Test
    public void by_default_statistics_are_not_recorded_for_now() {

        ThucydidesModuleWithMockEnvironmentVariables guiceModule = new ThucydidesModuleWithMockEnvironmentVariables();
        Injector injector = Guice.createInjector(guiceModule);
        EnvironmentVariables environmentVariables = injector.getInstance(EnvironmentVariables.class);
        environmentVariables.setProperty("thucydides.statistics.url", "jdbc:hsqldb:mem:defaultTestDatabase");

        TestOutcomeHistoryDAO testOutcomeHistoryDAO = injector.getInstance(HibernateTestOutcomeHistoryDAO.class);
        StatisticsListener statisticsListener = new StatisticsListener(testOutcomeHistoryDAO, environmentVariables);
        TestStatisticsProvider testStatisticsProvider = new TestStatisticsProvider(testOutcomeHistoryDAO);

        prepareTestData(statisticsListener);

        prepareDAOWithFixedClock();

        when(testOutcome.getResult()).thenReturn(TestResult.SUCCESS);

        statisticsListener.testFinished(testOutcome);
        statisticsListener.testSuiteFinished();

        List<TestRun> storedTestRuns = testStatisticsProvider.testRunsForTest(With.title(testOutcome.getTitle()));
        assertThat(storedTestRuns.size(), is(0));
    }

    @Test
    public void should_use_a_defined_project_key_to_group_results() {

        environmentVariables.setProperty("thucydides.project.key", "GIZMOS");

        recordTests(statisticsListener);
        recordTests(statisticsListener);

        TestStatistics testStatistics =  testStatisticsProvider.forProject("GIZMO")
                .statisticsForTests(With.title("Boat sales test"));

        assertThat(testStatistics.getTotalTestRuns(), is(16L));
    }


    @Test
    public void should_list_all_the_test_history_results_for_the_current_project() {

        List<TestRun> testRuns =  testStatisticsProvider.getAllTestHistories();

        assertThat(testRuns.size(), is(31));
    }

    @Test
    public void should_be_able_to_find_the_total_number_of_test_runs_for_a_given_test() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Boat sales test"));

        assertThat(testStatistics.getTotalTestRuns(), is(8L));
    }

    @Test
    public void should_be_able_to_find_the_total_number_of_passing_test_runs_for_a_given_test() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Boat sales test"));

        assertThat(testStatistics.getPassingTestRuns(), is(6L));
    }

    @Test
    public void should_not_fail_if_no_tests_are_available_with_a_given_name() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Does not exist"));

        assertThat(testStatistics.getTotalTestRuns(), is(0L));
    }

    @Test
    public void should_be_able_to_find_the_average_pass_rate_for_a_given_test() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Boat sales test"));

        assertThat(testStatistics.getOverallPassRate(), is(0.75));
    }

    @Test
    public void should_be_able_to_find_the_average_pass_rate_for_a_given_test_over_the_last_N_tests() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Boat sales test"));

        assertThat(testStatistics.getPassRate().overTheLast(4).testRuns(), is(1.0));
    }

    @Test
    public void should_be_able_to_find_the_average_pass_rate_for_a_given_tag_over_the_last_N_tests() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tag("Boat sales"));

        assertThat(testStatistics.getPassRate().overTheLast(4).testRuns(), is(1.0));
    }

    @Test
    public void should_not_fail_if_no_matching_test_runs_exist() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tag("does-not-exist"));

        assertThat(testStatistics.getTotalTestRuns(), is(0L));
    }

    @Test
    public void should_be_able_to_find_the_average_pass_rate_for_a_given_tag_over_the_last_8_tests() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tag("Boat sales"));

        assertThat(testStatistics.getPassRate().overTheLast(8).testRuns(), is(0.75));
    }

    @Test
    public void should_return_zero_for_pass_rate_if_no_tests_have_been_executed() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("An unexecuted test"));

        assertThat(testStatistics.getOverallPassRate(), is(0.0));
    }

    @WithTag(value="Online sales")
    class SomeTestCaseWithTagOnMethodAndClass {
        @WithTag(value="Car sales")
        public void some_test_method() {}
    }

    @Test
    public void should_record_associated_tags_with_a_test_run() {
        TestOutcome testOutcomeWithTags = TestOutcome.forTest("some_test_method", SomeTestCaseWithTagOnMethodAndClass.class);
        statisticsListener.testFinished(testOutcomeWithTags);

        List<TestRunTag> storedTags = testStatisticsProvider.findAllTags();
        assertThat(storedTags.isEmpty(), is(false));
    }

    @WithTag(value="Online sales", type="feature")
    class OnlineSalesTestCaseSample {
        @WithTag(value="Boat sales", type="story")
        public void boat_sales_test() {}

        @WithTag(value="Car sales", type="story")
        public void car_sales_test() {}

        @WithTag(value="House sales", type="story")
        public void house_sales_test() {}

        @WithTag(value="Gizmo sales", type="story")
        public void gizmo_sales_test() {}
    }

    @WithTag(value="Online sales", type="feature")
    class AnotherOnlineSalesTestCaseSample {
        @WithTag(value="Boat sales", type="story")
        public void more_boat_sales_test() {}

        @WithTag(value="Car sales", type="story")
        public void more_car_sales_test() {}
    }

    @Test
    public void should_retrieve_tags_associated_with_the_latest_test_run_of_a_test() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.title("Boat sales test"));

        List<TestRunTag> storedTags = testStatistics.getTags();
        assertThat(storedTags.size(), is(2));
    }

    @Test
    public void should_retrieve_a_list_of_all_available_tags_associated_with_the_latest_test_run_of_a_test() {

        List<TestRunTag> allTags = testStatisticsProvider.findAllTags();

        assertThat(allTags.size(), is(5));
    }

    @Test
    public void should_retrieve_a_list_of_all_available_tag_types_associated_with_the_latest_test_run_of_a_test() {

        List<String> allTagTypes = testStatisticsProvider.findAllTagTypes();

        assertThat(allTagTypes.size(), is(2));
        assertThat(allTagTypes, hasItems("feature","story"));
    }

    @Test
    public void should_retrieve_a_list_of_all_test_statistics_for_a_given_tag() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tag("Boat sales"));

        assertThat(testStatistics.getTotalTestRuns(), is(8L));
        assertThat(testStatistics.getPassingTestRuns(), is(6L));
        assertThat(testStatistics.getFailingTestRuns(), is(1L));
    }

    @Test
    public void should_retrieve_a_list_of_all_test_statistics_for_a_given_tag_type() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tagType("feature"));

        assertThat(testStatistics.getTotalTestRuns(), is(30L));
        assertThat(testStatistics.getPassingTestRuns(), is(18L));
        assertThat(testStatistics.getFailingTestRuns(), is(11L));
        assertThat(testStatistics.getTags().size(), is(2));
    }

    @Test
    public void should_not_fail_if_no_tags_of_this_type_exist() {

        TestStatistics testStatistics = testStatisticsProvider.statisticsForTests(With.tagType("does-not-exist"));

        assertThat(testStatistics.getTotalTestRuns(), is(0L));
        assertThat(testStatistics.getTags().size(), is(0));
    }


    /*
        - should retrieve test statistics for a given tag
        - should find aggregate data for tests for a given tag
     */

    static boolean runOnce = false;

    class SomeTestScenario {}

    private void prepareTestData(StatisticsListener statisticsListener) {
        if (!runOnce) {
            recordTests(statisticsListener);
            runOnce = true;
        }
    }

    private void recordTests(StatisticsListener statisticsListener) {
        statisticsListener.testSuiteStarted(SomeTestScenario.class);

        statisticsListener.testFinished(pendingTestFor("boat_sales_test"));
        statisticsListener.testFinished(failingTestFor("car_sales_test"));
        statisticsListener.testFinished(failingTestFor("house_sales_test"));

        statisticsListener.testFinished(failingTestFor("boat_sales_test"));
        statisticsListener.testFinished(failingTestFor("car_sales_test"));
        statisticsListener.testFinished(passingTestFor("house_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(failingTestFor("car_sales_test"));
        statisticsListener.testFinished(failingTestFor("house_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(failingTestFor("car_sales_test"));
        statisticsListener.testFinished(passingTestFor("house_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(passingTestFor("car_sales_test"));
        statisticsListener.testFinished(failingTestFor("house_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(passingTestFor("car_sales_test"));
        statisticsListener.testFinished(passingTestFor("house_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(failingTestFor("car_sales_test"));
        statisticsListener.testFinished(failingTestFor("house_sales_test"));
        statisticsListener.testFinished(passingTestFor("gizmo_sales_test"));

        statisticsListener.testFinished(passingTestFor("boat_sales_test"));
        statisticsListener.testFinished(passingTestFor("car_sales_test"));
        statisticsListener.testFinished(passingTestFor("house_sales_test"));
        statisticsListener.testFinished(failingTestFor("gizmo_sales_test"));

        statisticsListener.testFinished(passingTestFor("more_boat_sales_test"));
        statisticsListener.testFinished(passingTestFor("more_car_sales_test"));

        statisticsListener.testFinished(passingTestFor("more_boat_sales_test"));
        statisticsListener.testFinished(passingTestFor("more_car_sales_test"));
        statisticsListener.testSuiteFinished();
    }

    private TestOutcome failingTestFor(String methodName) {
        TestOutcome failingTestOutcome = TestOutcome.forTest(methodName, OnlineSalesTestCaseSample.class);
        failingTestOutcome.setTestFailureCause(new AssertionError("A nasty bug"));
        return failingTestOutcome;
    }

    private TestOutcome passingTestFor(String methodName) {
        TestOutcome passingTestOutcome = TestOutcome.forTest(methodName, OnlineSalesTestCaseSample.class);
        passingTestOutcome.setAnnotatedResult(TestResult.SUCCESS);
        return passingTestOutcome;
    }

    private TestOutcome pendingTestFor(String methodName) {
        TestOutcome passingTestOutcome = TestOutcome.forTest(methodName, OnlineSalesTestCaseSample.class);
        passingTestOutcome.setAnnotatedResult(TestResult.PENDING);
        return passingTestOutcome;
    }

    private void prepareDAOWithFixedClock() {
        when(clock.getCurrentTime()).thenReturn(JANUARY_1ST_2012);
        testOutcomeHistoryDAO = new HibernateTestOutcomeHistoryDAO(injector.getInstance(EntityManager.class),
                                                                   environmentVariables,
                                                                   clock);
        statisticsListener = new StatisticsListener(testOutcomeHistoryDAO, environmentVariables);
        testStatisticsProvider = new TestStatisticsProvider(testOutcomeHistoryDAO);
    }
}
