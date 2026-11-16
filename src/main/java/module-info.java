module org.example.perfect_number {
    requires javafx.controls;
    requires javafx.fxml;


    opens org.example.perfect_number to javafx.fxml;
    exports org.example.perfect_number;
}