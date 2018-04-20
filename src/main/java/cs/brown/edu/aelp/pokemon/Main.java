package cs.brown.edu.aelp.pokemon;

import com.google.common.collect.ImmutableMap;
import cs.brown.edu.aelp.networking.PlayerWebSocketHandler;
import cs.brown.edu.aelp.pokemmo.data.DataSource;
import cs.brown.edu.aelp.pokemmo.data.SQLDataSource;
import cs.brown.edu.aelp.pokemmo.map.World;
import cs.brown.edu.aelp.pokemmo.server.RegisterHandler;
import cs.brown.edu.aelp.util.JsonFile;
import freemarker.template.Configuration;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import spark.ExceptionHandler;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.Spark;
import spark.TemplateViewRoute;
import spark.template.freemarker.FreeMarkerEngine;

/**
 * The Main class entry point.
 */
public final class Main {

  // TODO: Actually load a world in when the server spins up
  private static World world;
  private static final int DEFAULT_PORT = 4567;
  private static DataSource datasrc;

  /**
   * @param args
   *          Command line arguments
   */
  public static void main(String[] args) {
    new Main().run(args);
  }

  /**
   * Private constructor for main.
   */
  private Main() {
  }

  /**
   * Entry point.
   *
   * @param args
   *          Command line arguments
   */
  private void run(String[] args) {

    // Parse command line arguments
    OptionParser parser = new OptionParser();
    parser.accepts("gui");
    parser.accepts("port").withRequiredArg().ofType(Integer.class)
        .defaultsTo(DEFAULT_PORT);
    OptionSet options = parser.parse(args);

    // ip, port, database, user, pass
    try {
      JsonFile cfg = new JsonFile("config/database_info.json");
      Main.datasrc = new SQLDataSource((String) cfg.getKey("ip"),
          (int) (double) cfg.getKey("port"), (String) cfg.getKey("database"),
          (String) cfg.getKey("username"), (String) cfg.getKey("password"));
    } catch (IOException | SQLException e) {
      System.out.println(
          "Something went wrong connecting to the database. Check your configuration file.");
      e.printStackTrace();
      return;
    }

    if (options.has("gui")) {
      runSparkServer((int) options.valueOf("port"));
    }

    System.out.println("Hello, World!");

    // temporary repl
    int count = 0;
    while (5 != 6) {
      if (count >= 100000) {
        count = 0;
      }
      if (count % 10000 == 0) {
        // this should be in our game loop and execute every tick
        PlayerWebSocketHandler.sendGamePackets();
      }
    }
  }

  private void runSparkServer(int port) {
    Spark.webSocket("/game", PlayerWebSocketHandler.class);

    Spark.port(port);
    Spark.externalStaticFileLocation("src/main/resources/static");
    Spark.exception(Exception.class, new ExceptionPrinter());

    FreeMarkerEngine freeMarker = createEngine();

    Spark.get("/main", new IndexHandler(), freeMarker);
    Spark.get("/", new IndexHandler(), freeMarker);
    Spark.post("/register", new RegisterHandler(Main.getDataSource()));

    // Setup Spark Routes
  }

  /**
   * Handle requests to the front page of our website.
   */
  private static class IndexHandler implements TemplateViewRoute {
    @Override
    public ModelAndView handle(Request req, Response res) {
      return new ModelAndView(ImmutableMap.of(), "main.ftl");
    }
  }

  // Spark set up:

  private static FreeMarkerEngine createEngine() {
    Configuration config = new Configuration();
    File templates = new File("src/main/resources/spark/template/freemarker");
    try {
      config.setDirectoryForTemplateLoading(templates);
    } catch (IOException ioe) {
      System.out.printf("ERROR: Unable use %s for template loading.%n",
          templates);
      System.exit(1);
    }
    return new FreeMarkerEngine(config);
  }

  // Spark error handling:

  /**
   * Display an error page when an exception occurs in the server.
   */
  private static class ExceptionPrinter implements ExceptionHandler<Exception> {
    @Override
    public void handle(Exception e, Request req, Response res) {
      res.status(500);
      StringWriter stacktrace = new StringWriter();
      try (PrintWriter pw = new PrintWriter(stacktrace)) {
        pw.println("<pre>");
        e.printStackTrace(pw);
        pw.println("</pre>");
      }
      res.body(stacktrace.toString());
    }
  }

  public static World getWorld() {
    return Main.world;
  }

  public static DataSource getDataSource() {
    return Main.datasrc;
  }

}
