package org.texttechnologylab.DockerUnifiedUIMAInterface.driver;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CASException;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.InvalidXMLException;
import org.json.JSONObject;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.IDUUICommunicationLayer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.DUUILuaContext;
import org.texttechnologylab.DockerUnifiedUIMAInterface.pipeline_storage.DUUIPipelineDocumentPerformance;
import org.texttechnologylab.DockerUnifiedUIMAInterface.segmentation.DUUISegmentationStrategy;
import org.xml.sax.SAXException;
import podman.client.PodmanClient;
import podman.client.containers.ContainerCreateOptions;
import podman.client.containers.ContainerDeleteOptions;
import podman.client.containers.ContainerInspectOptions;
import podman.client.images.ImagePullOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.String.format;
import static org.awaitility.Awaitility.await;
import static org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer.getLocalhost;
import static org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIDockerDriver.responsiveAfterTime;

public class DUUIPodmanDriver implements IDUUIDriverInterface {

    private PodmanClient _interface = null;
    private HttpClient _client;

    private Vertx _vertx = null;
    private DUUILuaContext _luaContext = null;
    private int _container_timeout;

    private HashMap<String, DUUIDockerDriver.InstantiatedComponent> _active_components;


    public DUUIPodmanDriver() throws IOException, SAXException {

        VertxOptions vertxOptions = new VertxOptions().setPreferNativeTransport(true);
        _vertx = Vertx.vertx(vertxOptions);
        _client = HttpClient.newHttpClient();


        System.out.printf("[PodmanDriver] Is Native Transport Enabled: %s\n", _vertx.isNativeTransportEnabled());


        PodmanClient.Options options = new PodmanClient.Options().setSocketPath(podmanSocketPath());

        _interface = PodmanClient.create(_vertx, options);
        _container_timeout = 10;
        _active_components = new HashMap<String, DUUIDockerDriver.InstantiatedComponent>();
        _luaContext = null;

    }

    public static String podmanSocketPath() {
        String path = System.getenv("PODMAN_SOCKET_PATH");

        if (path == null) {
            String uid = System.getenv("UID");
            if (uid == null) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("id", "-u");
                    Process process = pb.start();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    uid = reader.readLine(); // UID aus der Ausgabe lesen
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            path = "/run/user/" + uid + "/podman/podman.sock";
        }

        return path;
    }

    static <T> T awaitResult(Future<T> future) throws Throwable {
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        future.onComplete(res -> {
            if (res.succeeded()) {
                result.set(res.result());
            } else {
                failure.set(res.cause());
            }
            done.set(true);
        });
        await().untilTrue(done);
        if (failure.get() != null) {
            throw failure.get();
        } else {
            return result.get();
        }
    }

    @Override
    public void setLuaContext(DUUILuaContext luaContext) {
        this._luaContext = luaContext;
    }

    @Override
    public boolean canAccept(DUUIPipelineComponent component) throws InvalidXMLException, IOException, SAXException {
        return component.getDockerImageName() != null;
    }

    @Override
    public String instantiate(DUUIPipelineComponent component, JCas jc, boolean skipVerification, AtomicBoolean shutdown) throws Exception {

        String uuid = UUID.randomUUID().toString();
        while (_active_components.containsKey(uuid.toString())) {
            uuid = UUID.randomUUID().toString();
        }


        DUUIDockerDriver.InstantiatedComponent comp = new DUUIDockerDriver.InstantiatedComponent(component);

        // Inverted if check because images will never be pulled if !comp.getImageFetching() is checked.
        if (comp.getImageFetching()) {
            if (comp.getUsername() != null) {
                System.out.printf("[PodmanDriver] Attempting image %s download from secure remote registry\n", comp.getImageName());
            }
            _interface.images().pull(comp.getImageName(), new ImagePullOptions());
            if (shutdown.get()) {
                return null;
            }

            System.out.printf("[PodmanDriver] Pulled image with id %s\n", comp.getImageName());
        } else {
//            _interface.pullImage(comp.getImageName());
            if (!_interface.images().exists(comp.getImageName()).succeeded()) {
                throw new InvalidParameterException(format("Could not find local image \"%s\". Did you misspell it or forget with .withImageFetching() to fetch it from remote registry?", comp.getImageName()));
            }
        }
        System.out.printf("[PodmanDriver] Assigned new pipeline component unique id %s\n", uuid);

        _active_components.put(uuid, comp);
        // TODO: Fragen, was hier genau gemacht wird.
        for (int i = 0; i < comp.getScale(); i++) {
            if (shutdown.get()) {
                return null;
            }


            ContainerCreateOptions pOptions = new ContainerCreateOptions();
            pOptions.image(comp.getImageName());
            pOptions.remove(true);

            pOptions.publishImagePorts(true);


            JsonObject pObject = null;
            JsonObject iObject = null;
            String containerId = "";
            int port = -1;
            try {
                pObject = awaitResult(_interface.containers().create(pOptions));
                containerId = pObject.getString("Id");

                _interface.containers().start(containerId);

                iObject = awaitResult(_interface.containers().inspect(containerId, new ContainerInspectOptions().setSize(false)));
                JSONObject nObject = new JSONObject(iObject);
//                System.out.println(nObject.toString(1));
                port = nObject.getJSONObject("map").getJSONObject("HostConfig").getJSONObject("PortBindings").getJSONArray("9714/tcp").getJSONObject(0).getInt("HostPort");

//
//                Thread.sleep(3000l);
//
//                _interface.containers().stop(containerId, true, 10);
//                System.out.println("stop");
//                Thread.sleep(3000l);
//
//
//                _interface.containers().delete(containerId, new ContainerDeleteOptions().setTimeout(100).setIgnore(true));
//                System.out.println("remove");
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

            try {
                if (port == 0) {
                    throw new UnknownError("Could not read the container port!");
                }
                final int iCopy = i;
                final String uuidCopy = uuid;
                IDUUICommunicationLayer layer = responsiveAfterTime(getLocalhost() + ":" + String.valueOf(port), jc, _container_timeout, _client, (msg) -> {
                    System.out.printf("[PodmanDriver][%s][Podman Replication %d/%d] %s\n", uuidCopy, iCopy + 1, comp.getScale(), msg);
                }, _luaContext, skipVerification);
                System.out.printf("[PodmanDriver][%s][Podman Replication %d/%d] Container for image %s is online (URL http://127.0.0.1:%d) and seems to understand DUUI V1 format!\n", uuid, i + 1, comp.getScale(), comp.getImageName(), port);

                comp.addInstance(new DUUIDockerDriver.ComponentInstance(containerId, port, layer));
            } catch (Exception e) {
                //_interface.stop_container(containerid);
                //throw e;
            }


        }
        return shutdown.get() ? null : uuid;

    }

    @Override
    public void printConcurrencyGraph(String uuid) {

    }

    @Override
    public TypeSystemDescription get_typesystem(String uuid) throws InterruptedException, IOException, SAXException, CompressorException, ResourceInitializationException {
        DUUIDockerDriver.InstantiatedComponent comp = _active_components.get(uuid);
        if (comp == null) {
            throw new InvalidParameterException("Invalid UUID, this component has not been instantiated by the local Driver");
        }
        return IDUUIInstantiatedPipelineComponent.getTypesystem(uuid, comp);
    }

    @Override
    public void run(String uuid, JCas aCas, DUUIPipelineDocumentPerformance perf, DUUIComposer composer) throws InterruptedException, IOException, SAXException, AnalysisEngineProcessException, CompressorException, CASException {
        long mutexStart = System.nanoTime();
        DUUIDockerDriver.InstantiatedComponent comp = _active_components.get(uuid);
        if (comp == null) {
            throw new InvalidParameterException("Invalid UUID, this component has not been instantiated by the local Driver");
        }
        IDUUIInstantiatedPipelineComponent.process(aCas, comp, perf);

    }

    @Override
    public boolean destroy(String uuid) {
        DUUIDockerDriver.InstantiatedComponent comp = _active_components.remove(uuid);
        if (comp == null) {
            throw new InvalidParameterException("Invalid UUID, this component has not been instantiated by the local Driver");
        }
        if (!comp.getRunningAfterExit()) {
            int counter = 1;
            for (DUUIDockerDriver.ComponentInstance inst : comp.getInstances()) {
                System.out.printf("[PodmanDriver][Replication %d/%d] Stopping docker container %s...\n", counter, comp.getInstances().size(), inst.getContainerId());
                _interface.containers().stop(inst.getContainerId(), false, 1);
                _interface.containers().delete(inst.getContainerId(), new ContainerDeleteOptions().setTimeout(1).setIgnore(true));

                counter += 1;
            }
        }

        return true;
    }

    @Override
    public void shutdown() {
        for (String s : _active_components.keySet()) {
            destroy(s);
        }
        try {
            Thread.sleep(3000l);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Component {
        private DUUIPipelineComponent _component;

        public Component(String target) throws URISyntaxException, IOException {
            _component = new DUUIPipelineComponent();
            _component.withDockerImageName(target);
        }

        public Component(DUUIPipelineComponent pComponent) throws URISyntaxException, IOException {
            _component = pComponent;
        }

        public DUUIPodmanDriver.Component withParameter(String key, String value) {
            _component.withParameter(key, value);
            return this;
        }

        public DUUIPodmanDriver.Component withView(String viewName) {
            _component.withView(viewName);
            return this;
        }

        public DUUIPodmanDriver.Component withSourceView(String viewName) {
            _component.withSourceView(viewName);
            return this;
        }

        public DUUIPodmanDriver.Component withTargetView(String viewName) {
            _component.withTargetView(viewName);
            return this;
        }

        public DUUIPodmanDriver.Component withDescription(String description) {
            _component.withDescription(description);
            return this;
        }

        public DUUIPodmanDriver.Component withScale(int scale) {
            _component.withScale(scale);
            return this;
        }

        public DUUIPodmanDriver.Component withRegistryAuth(String username, String password) {
            _component.withDockerAuth(username, password);
            return this;
        }

        public DUUIPodmanDriver.Component withImageFetching() {
            return withImageFetching(true);
        }

        public DUUIPodmanDriver.Component withImageFetching(boolean imageFetching) {
            _component.withDockerImageFetching(imageFetching);
            return this;
        }

        public DUUIPodmanDriver.Component withGPU(boolean gpu) {
            _component.withDockerGPU(gpu);
            return this;
        }

        public DUUIPodmanDriver.Component withRunningAfterDestroy(boolean run) {
            _component.withDockerRunAfterExit(run);
            return this;
        }

        public DUUIPodmanDriver.Component withSegmentationStrategy(DUUISegmentationStrategy strategy) {
            _component.withSegmentationStrategy(strategy);
            return this;
        }

        public <T extends DUUISegmentationStrategy> DUUIPodmanDriver.Component withSegmentationStrategy(Class<T> strategyClass) throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
            _component.withSegmentationStrategy(strategyClass.getDeclaredConstructor().newInstance());
            return this;
        }

        public DUUIPipelineComponent build() {
            _component.withDriver(DUUIPodmanDriver.class);
            return _component;
        }

        public DUUIPodmanDriver.Component withName(String name) {
            _component.withName(name);
            return this;
        }
    }

}
