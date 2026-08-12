import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.jcas.JCas;
import org.junit.Test;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIDockerDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIRemoteDriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIUIMADriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.DUUILuaContext;

import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;

public class LoggingTest {

    @Test
    public void runDUUI() throws Exception{

        JCas jCas = JCasFactory.createJCas();
        jCas.setDocumentText("Mensch (Homo sapiens, lateinisch für „verstehender, verständiger“ oder „weiser, gescheiter, kluger, vernünftiger Mensch“) ist nach der biologischen Systematik eine Art der Gattung Homo aus der Familie der Menschenaffen, die zur Ordnung der Primaten und damit zu den Höheren Säugetieren gehört.");
        jCas.setDocumentLanguage("de");

        int iWorkers = 1;
        DUUILuaContext ctx = new DUUILuaContext().withJsonLibrary();
        // Instanziierung des Composers, mit einigen Parametern
        DUUIComposer composer = new DUUIComposer()
                .withSkipVerification(true)     // wir überspringen die Verifikation aller Componenten =)
                .withLuaContext(ctx)            // wir setzen den definierten Kontext
                .withWorkers(1)          // wir geben dem Composer eine Anzahl an Threads mit.
                .withDebugLevel(DUUIComposer.DebugLevel.TRACE) // Konsolen-Schwelle: ohne das keine Ausgeben
                .withDebugColorful(true)
                .withDebugSeverity(true)
                .withDebugSource(true);


        DUUIUIMADriver uima_driver = new DUUIUIMADriver();
        DUUIRemoteDriver remoteDriver = new DUUIRemoteDriver();
        DUUIDockerDriver dockerDriver = new DUUIDockerDriver();

        // Hinzufügen der einzelnen Driver zum Composer
        composer.addDriver(uima_driver, remoteDriver, dockerDriver);

        /*composer.add(new DUUIDockerDriver.Component("docker.texttechnologylab.org/duui-gnfinder-v2:latest")
                        .withTargetView("newView")
                        .withImageFetching()
                .build());
*/
        /*composer.add(new DUUIDockerDriver.Component("docker.texttechnologylab.org/duui-gnfinder-v2:latest")
                    .withParameter("sources", "[1,2,3,4,5,6,7]")
                    .withParameter("oddsDetails", "true")
                    .withParameter("allMatches", "true")
                    .withParameter("ambiguousNames", "true")
                .withImageFetching()
                .build());*/

        composer.add(new DUUIRemoteDriver.Component("http://localhost:25591")
                .withWorkers(1)
                .withTargetView("out")
                .build());

        composer.run(jCas);
    }

}
