package Quiz.ClientSide;

import Quiz.ServerSide.Database;
import Quiz.ServerSide.Initializer;
import Quiz.ServerSide.Question;
import Quiz.ServerSide.Response;
import javafx.application.Platform;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static Quiz.ClientSide.Constants.GAMEPROPERTIES;

/**
 * Created by Robin Martinsson
 * Date:    2020-11-17
 * Time:    15:33
 * Project: Inlämning04
 * Copyright: MIT
 */

public class ClientProtocol {

    private Properties gameProperties;
    private int questionsAmount;
    private int roundsAmount;
    private int categoriesAmount;

    Database database;
    private String player1Name;
    private String player2Name;
    private boolean bothConnected;
    private ObjectOutputStream player1out;
    private ObjectOutputStream player2out;
    private Question p1CurrentQuestion;
    private Question p2CurrentQuestion;
    int player1Score = 0;
    int player2Score = 0;
//    private int counter = 4;

    private boolean[][] p1Answers;
    private boolean[][] p2Answers;

    private int p1CurrentQuestionCounter;
    private int p2CurrentQuestionCounter;
    private List<Question> currentGenre;
    private int currentRound;

    private boolean p1RoundFinished;
    private boolean p2RoundFinished;

    public enum State {
        WAITING, PLAYER_1_CONNECTED, BOTH_CONNECTED
    }

    private State currentState;

    public ClientProtocol(Database database) {

        this.gameProperties = new Properties();
        this.getGameProperties();
        this.questionsAmount = Integer.parseInt(this.gameProperties.getProperty("Questions", "2"));
        this.roundsAmount = Integer.parseInt(this.gameProperties.getProperty("Rounds", "2"));
        this.categoriesAmount = Integer.parseInt(this.gameProperties.getProperty("Categories", "4"));
        this.currentState = State.WAITING;
        this.database = database;
        this.p1CurrentQuestionCounter = 0;
        this.p2CurrentQuestionCounter = 0;
        this.p1Answers = new boolean[this.roundsAmount][this.questionsAmount];
        this.p2Answers = new boolean[this.roundsAmount][this.questionsAmount];
    }

    public synchronized void ProcessInput(Object in, int playerId) {

        if (this.areBothConnected())
            this.currentState = State.BOTH_CONNECTED;

        switch (this.currentState) {
            case WAITING -> {
                if (in instanceof String && ((String) in).equalsIgnoreCase("init")) {
                    try {
                        if (playerId == 2) {
                            System.out.println("Player 2 connected first");
                        } else {
                            sendObject(new Initializer(), 1);
                            currentState = State.PLAYER_1_CONNECTED;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            case PLAYER_1_CONNECTED -> {
                if (in instanceof String && ((String) in).equalsIgnoreCase("init") && playerId == 2) {

                    this.currentGenre = database.getQuestionByCategory("Music");

                    this.p1CurrentQuestion = this.currentGenre.get(this.p1CurrentQuestionCounter);
                    this.p2CurrentQuestion = this.currentGenre.get(this.p2CurrentQuestionCounter);
                    try {
                        player2out.writeObject(new Initializer()); // skicka init till player2
                        sendObject(new Initializer(), 2);
                        sendObject(new Initializer(this.player1Name, this.player2Name, this.p1CurrentQuestion), 1);
                        sendObject(new Initializer(this.player2Name, this.player1Name, this.p2CurrentQuestion), 2);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    this.bothConnected = true;
                    currentState = State.BOTH_CONNECTED;
                    System.out.println("protocol - BOTH_CONNECTED");
                }
            }
            case BOTH_CONNECTED -> {

                if (in instanceof Request) {
                    if (((Request) in).getStatus() == RequestStatus.ANSWER) {
                        if (playerId == 1) {
                            boolean answer = this.p1CurrentQuestion.isRightAnswer(((Request) in).getAnswerText());

                            sendObject(new Response(
                                            Response.ResponseStatus.CHECKED_ANSWER, answer),
                                    playerId);

                            this.p1Answers[this.currentRound][this.p1CurrentQuestionCounter] = answer;
                        }

                        if (playerId == 2) {
                            boolean answer = this.p2CurrentQuestion.isRightAnswer(((Request) in).getAnswerText());

                            sendObject(new Response(
                                            Response.ResponseStatus.CHECKED_ANSWER, answer),
                                    playerId);

                            this.p2Answers[this.currentRound][this.p2CurrentQuestionCounter] = answer;
                        }
                    }

                    if (((Request) in).getStatus() == RequestStatus.NEXT_QUESTION) {
                        if (playerId == 1) {
                            this.p1CurrentQuestionCounter++;
                            if (this.p1CurrentQuestionCounter < this.questionsAmount) {
                                this.p1CurrentQuestion = this.currentGenre.get(this.p1CurrentQuestionCounter);
                                sendObject(new Response(Response.ResponseStatus.NEW_QUESTION, this.p1CurrentQuestion), playerId);
                            } else {
                                sendObject(new Response(Response.ResponseStatus.WAIT), playerId);
                                updatePlayerScore(1);
                                this.p1RoundFinished = true;
                            }
                        }

                        if (playerId == 2) {
                            this.p2CurrentQuestionCounter++;
                            if (this.p2CurrentQuestionCounter < this.questionsAmount) {
                                this.p2CurrentQuestion = this.currentGenre.get(this.p2CurrentQuestionCounter);
                                sendObject(new Response(Response.ResponseStatus.NEW_QUESTION, this.p2CurrentQuestion), playerId);
                            } else {
                                sendObject(new Response(Response.ResponseStatus.WAIT), playerId);
                                updatePlayerScore(2);
                                System.out.println("player 2 score = " + player2Score);
                                this.p2RoundFinished = true;
                            }
                        }
                    }
                }

                if (p1RoundFinished && p2RoundFinished) {
                    this.p1RoundFinished = false;
                    this.p2RoundFinished = false;
                    this.p1CurrentQuestionCounter = 0;
                    this.p2CurrentQuestionCounter = 0;

                    sendObject(new Response(Response.ResponseStatus.RESULTS, this.currentRound, p1Answers[this.currentRound], p2Answers[this.currentRound], player1Score, player2Score), 1);
                    sendObject(new Response(Response.ResponseStatus.RESULTS, this.currentRound, p2Answers[this.currentRound], p1Answers[this.currentRound], player2Score, player1Score), 2);
                    this.currentRound++;

                    // TODO - vid knapptryck (fortsätt) efter resultatWindow
                    // Om ronden är jämn väljer player 1 kategori annars player 2
//                    sendObject(new Response(Response.ResponseStatus.NEXT_ROUND), (this.currentRound % 2 == 0 ? 1 : 2));
                }
            }
        }
    }

    public void setPlayer(String playerName, int playerId) {
        if (playerId == 1 && this.player1Name == null) {
            System.out.println("set player 1 to " + playerName);
            this.player1Name = playerName;
        } else if (playerId == 2 && this.player2Name == null) {
            System.out.println("set player 2 to " + playerName);
            this.player2Name = playerName;
        } else {
            System.out.println("both names already set.");
        }
    }

    public boolean areBothConnected() {
        return bothConnected;
    }

    public void setPlayerOuts(ObjectOutputStream player1Out, ObjectOutputStream player2out) {
        this.player1out = player1Out;
        this.player2out = player2out;
    }

    private void sendObject(Object object, int playerId) {
        try {
            if (playerId == 1)
                this.player1out.writeObject(object);
            else if (playerId == 2)
                this.player2out.writeObject(object);
            else
                throw new Exception();

        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("sent " + object + " to player " + playerId);
    }

    private void getGameProperties() {

        if (!new File("src/main/java/Quiz/ClientSide/GameSetup.properties").exists()) {
            try {
                Files.createFile(Path.of("src/main/java/Quiz/ClientSide/GameSetup.properties"));

                try (FileWriter fw = new FileWriter("src/main/java/Quiz/ClientSide/GameSetup.properties")) {
                    fw.append("Categories=4\n");
                    fw.append("Questions=2\n");
                    fw.append("Rounds=2");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                this.gameProperties.load(new FileInputStream("src/main/java/Quiz/ClientSide/GameSetup.properties"));
                this.gameProperties.setProperty("Categories", "4");
                this.gameProperties.setProperty("Questions", "2");
                this.gameProperties.setProperty("Rounds", "2");
                System.out.println("File created.");
            } catch (IOException e) {
                System.out.println("Failed to create file.");
                e.printStackTrace();
            }
        }

        try {
            this.gameProperties.load(new FileInputStream("src/main/java/Quiz/ClientSide/GameSetup.properties"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updatePlayerScore(int playerId) {
        if (playerId == 1) {
            for (boolean ans : p1Answers[currentRound]) {
                if (ans)
                    this.player1Score++;
            }
        }

        if (playerId == 2) {
            for (boolean ans : p2Answers[currentRound]) {
                if (ans)
                    this.player2Score++;
            }
        }
    }
}