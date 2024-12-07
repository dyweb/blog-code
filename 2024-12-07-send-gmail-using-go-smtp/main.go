package main

import (
	"fmt"
	"log"
	"net/smtp"
	"os"
)

// export GMAIL_APP_PASSWORD=your_app_password
// go run main.go foo@gmail.com bar@gmail.com
func main() {
	from := os.Args[1]
	to := os.Args[2]
	// Create password at https://myaccount.google.com/apppasswords for foo@gmail.com
	appPassword := os.Getenv("GMAIL_APP_PASSWORD")
	if err := send(from, to, appPassword); err != nil {
		log.Fatal(err)
	}
}

// https://gist.github.com/jpillora/cb46d183eca0710d909a
func send(from, to, appPassword string) error {
	auth := smtp.PlainAuth(
		"",
		from,
		appPassword,
		"smtp.gmail.com",
	)

	// https://en.wikipedia.org/wiki/Simple_Mail_Transfer_Protocol#SMTP_transport_example
	subject := "This subject"
	body := "This first line\n" +
		"This second line\n" +
		"Here is a link https://google.com"
	msg := "From: " + from + "\n" +
		"To: " + to + "\n" +
		"Subject: " + subject + "\n\n" +
		body

	// https://www.cloudflare.com/learning/email-security/smtp-port-25-587/
	if err := smtp.SendMail("smtp.gmail.com:587", auth, from, []string{to}, []byte(msg)); err != nil {
		return fmt.Errorf("send email to %s failed: %w", to, err)
	}
	log.Printf("Sent email from %s to %s", from, to)
	return nil
}
