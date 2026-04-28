pipeline {
    agent any

    stages {

        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/Naresh300898/website-monitoring-automation.git'
            }
        }

        stage('Build') {
            steps {
                sh 'mvn clean install'
            }
        }

        stage('Run Test') {
            steps {
                sh 'mvn test'
            }
        }
    }
}
